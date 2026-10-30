"""Validate Play files and package the existing AAB locally. Does not upload anything."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
METADATA = ROOT / "fastlane/metadata/android/en-US"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def check(condition, message):
    if not condition:
        raise SystemExit(message)


def png(path):
    data = path.read_bytes()
    check(data[:8] == b"\x89PNG\r\n\x1a\n", f"Invalid PNG: {path}")
    width, height, depth, colour, _, _, _ = struct.unpack(">IIBBBBB", data[16:29])
    offset = 8
    ended = False
    while offset < len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + length]
        crc = struct.unpack(">I", data[offset + 8 + length:offset + 12 + length])[0]
        check(zlib.crc32(kind + payload) == crc, f"PNG CRC failure: {path}")
        offset += length + 12
        if kind == b"IEND":
            ended = True
            break
    check(ended and depth == 8, f"Incomplete PNG or unsupported depth: {path}")
    return {"width": width, "height": height, "colour_type": colour, "bytes": len(data), "sha256": sha(data)}


def jdk_tool(name):
    executable = name + (".exe" if os.name == "nt" else "")
    candidates = [Path(os.environ["JAVA_HOME"]) / "bin" / executable] if os.environ.get("JAVA_HOME") else []
    candidates += [ROOT / ".build-tools/jdk/bin" / executable]
    return next((str(p) for p in candidates if p.is_file()), shutil.which(name) or name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-only", action="store_true", help="Validate committed metadata and images without a build")
    parser.add_argument("--require-signed", action="store_true", help="Fail unless the bundle has a valid non-debug upload signature")
    args = parser.parse_args()
    check(not (args.check_only and args.require_signed), "--require-signed needs a bundle, not --check-only")
    build = (ROOT / "app/build.gradle.kts").read_text()
    version = re.search(r'versionName = "([^"]+)"', build)[1]
    code = int(re.search(r'versionCode = (\d+)', build)[1])
    lengths = {}
    for name, limit in (("title", 30), ("short_description", 80), ("full_description", 4000), (f"changelogs/{code}", 500)):
        text = (METADATA / f"{name}.txt").read_text().strip()
        check(0 < len(text) <= limit, f"{name} must be 1–{limit} characters")
        check("REPLACE_" not in text and "TODO" not in text, f"Unfinished store text: {name}")
        if name in ("title", "short_description"):
            check("\n" not in text, f"{name} must be one line")
        lengths[name] = len(text)
    image_records = {}
    images = METADATA / "images"
    for name in ("icon.png", "featureGraphic.png"):
        image_records[name] = png(images / name)
    icon = image_records["icon.png"]
    feature = image_records["featureGraphic.png"]
    check((icon["width"], icon["height"], icon["colour_type"]) == (512, 512, 6) and icon["bytes"] <= 1024 * 1024, "Icon must be 512×512 RGBA PNG, at most 1 MB")
    check((feature["width"], feature["height"], feature["colour_type"]) == (1024, 500, 2), "Feature graphic must be 1024×500 RGB PNG")
    screenshots = sorted((images / "phoneScreenshots").glob("*.png"))
    check(2 <= len(screenshots) <= 8, "Provide 2–8 phone screenshots")
    for path in screenshots:
        info = png(path)
        w, h = info["width"], info["height"]
        check(info["colour_type"] == 2 and min(w, h) >= 320 and max(w, h) <= 3840 and max(w, h) <= 2 * min(w, h), f"Invalid screenshot dimensions/colour: {path}")
        check(info["bytes"] <= 8 * 1024 * 1024, f"Screenshot exceeds 8 MB: {path}")
        image_records[path.relative_to(images).as_posix()] = info
    check(len({image_records[path.relative_to(images).as_posix()]["sha256"] for path in screenshots}) == len(screenshots), "Screenshots must show distinct views")
    details = json.loads((ROOT / "publishing/console-details.json").read_text())
    report = {"application_id": "app.tideline", "version_name": version, "version_code": code, "listing_characters": lengths, "images": image_records, "submitted": False}
    if args.check_only:
        print(json.dumps(report, indent=2))
        return

    bundle = ROOT / "app/build/outputs/bundle/release/app-release.aab"
    check(bundle.is_file(), "Build :app:bundleRelease first")
    manifests = list((ROOT / "app/build/intermediates/merged_manifests/release").rglob("AndroidManifest.xml"))
    check(len(manifests) == 1, "Build the release merged manifest first")
    manifest = ET.parse(manifests[0]).getroot()
    check(manifest.get("package") == "app.tideline" and manifest.get(ANDROID + "versionCode") == str(code) and manifest.get(ANDROID + "versionName") == version, "Release manifest does not match source version/package")
    check(int(manifest.find("uses-sdk").get(ANDROID + "targetSdkVersion")) >= 36, "Unexpected target API")
    permissions = sorted(p.get(ANDROID + "name") for p in manifest.findall("uses-permission"))
    check("android.permission.INTERNET" not in permissions and "android.permission.ACCESS_FINE_LOCATION" not in permissions, "Recheck policy: unexpected Internet/fine-location permission")
    check(manifest.find("application").get(ANDROID + "debuggable", "false") == "false", "Release is debuggable")
    with zipfile.ZipFile(bundle) as archive:
        check(archive.testzip() is None, "Bundle ZIP CRC failure")
        check(not any(n.endswith(".so") for n in archive.namelist()), "Native libraries added: review platform/page-size compatibility")
        for asset, source in (("tideline-global.tpk", "data/packs/tideline-global.tpk"), ("PRIVACY.md", "PRIVACY.md"), ("DATA_NOTICES.txt", "docs/DATA_NOTICES.txt")):
            check(sha(archive.read("base/assets/" + asset)) == sha((ROOT / source).read_bytes()), f"Bundle asset differs: {asset}")
        signature_present = any(re.fullmatch(r"META-INF/[^/]+\.(RSA|DSA|EC)", n, re.I) for n in archive.namelist())
    signed = False
    certificate_sha256 = None
    if signature_present:
        verified = subprocess.run([jdk_tool("jarsigner"), "-J-Duser.language=en", "-verify", str(bundle)], capture_output=True, text=True, check=True)
        message = verified.stdout + verified.stderr
        check("jar verified." in message and "unsigned entries" not in message, "Bundle signature could not be fully verified")
        certificate = subprocess.check_output([jdk_tool("keytool"), "-J-Duser.language=en", "-printcert", "-jarfile", str(bundle)], text=True)
        check("Android Debug" not in certificate and "androiddebugkey" not in certificate.lower(), "Debug signing is not accepted for publishing")
        digest = re.search(r"SHA256:\s*([0-9A-F:]+)", certificate)
        check(digest is not None, "Cannot identify upload certificate")
        certificate_sha256 = digest[1]
        signed = True
    check(signed or not args.require_signed, "Bundle is unsigned. Configure your own upload key; see docs/PLAY_STORE.md. Nothing packaged for upload.")
    readiness = []
    if not signed:
        readiness.append("Configure an upload key and rebuild; this bundle is UNSIGNED and cannot be uploaded")
    if not details.get("support_email"):
        readiness.append("Choose the required public support email")
    if details.get("repository_private_during_testing"):
        readiness.append("Make the privacy-policy URL public and verify it while signed out")
    if not details.get("background_location_demo_video_url"):
        readiness.append("Record the background-location review video and add a reviewer-accessible URL")
    readiness += ["Complete real background-location/permission/reboot/battery tests", "Complete Console declarations, IARC, audience, pricing/countries and required account testing"]
    report.update({"bundle_sha256": sha(bundle.read_bytes()), "bundle_bytes": bundle.stat().st_size, "upload_signature_verified": signed, "upload_certificate_sha256": certificate_sha256, "release_permissions": permissions, "remaining_publisher_steps": readiness, "ready_for_submission": False})
    status = "signed" if signed else "UNSIGNED"
    destination = ROOT / f"dist/play/tideline-{version}-{code}-{status}"
    check(not destination.exists(), f"Output already exists; keep it as a previous snapshot or move it before rerunning: {destination}")
    destination.mkdir(parents=True)
    shutil.copy2(bundle, destination / f"tideline-{version}-{code}-{status}.aab")
    shutil.copytree(ROOT / "fastlane/metadata", destination / "fastlane/metadata")
    (destination / "publishing").mkdir()
    # Explicit allowlist: never copy key.properties, a keystore or local credentials.
    for name in ("DATA_SAFETY.md", "BACKGROUND_LOCATION.md", "console-details.json", "asset-provenance.txt"):
        shutil.copy2(ROOT / "publishing" / name, destination / "publishing" / name)
    shutil.copy2(ROOT / "docs/PLAY_STORE.md", destination / "README.md")
    shutil.copy2(ROOT / "PRIVACY.md", destination / "PRIVACY.md")
    (destination / "docs").mkdir()
    shutil.copy2(ROOT / "docs/DATA_NOTICES.txt", destination / "docs/DATA_NOTICES.txt")
    shutil.copytree(ROOT / "docs/site", destination / "docs/site")
    (destination / "release-report.json").write_text(json.dumps(report, indent=2) + "\n")
    checksums = "".join(f"{sha(path.read_bytes())}  {path.relative_to(destination).as_posix()}\n" for path in sorted(destination.rglob("*")) if path.is_file())
    (destination / "SHA256SUMS").write_text(checksums)
    archive = shutil.make_archive(str(destination), "zip", destination)
    print(f"Prepared {destination.relative_to(ROOT)}\nArchive: {Path(archive).relative_to(ROOT)}\nUpload signing: {signed}\nNot submitted. Publisher steps are recorded in release-report.json.")


if __name__ == "__main__":
    main()
