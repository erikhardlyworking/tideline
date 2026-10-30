"""Capture native Play artwork and app-only screenshots; never capture the launcher."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
IMAGES = ROOT / "fastlane/metadata/android/en-US/images"
NAMES = ["icon.png", "featureGraphic.png"] + [
    f"phoneScreenshots/{name}.png" for name in
    ("01-widget-and-tide", "02-day-forecast", "03-fixed-location", "04-appearance")
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="USB device serial; required if more than one is connected")
    parser.add_argument("--no-build", action="store_true", help="Use the existing debug/test APKs")
    args = parser.parse_args()
    if not args.no_build:
        subprocess.run(["python3", "tools/task.py", "device-apk"], cwd=ROOT, check=True)
    local_adb = ROOT / ".build-tools/android-sdk/platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    adb = [str(local_adb) if local_adb.exists() else (shutil.which("adb") or "adb")]
    devices = subprocess.check_output(adb + ["devices"], text=True)
    serials = [line.split()[0] for line in devices.splitlines()[1:] if line.endswith("\tdevice")]
    serial = args.serial or (serials[0] if len(serials) == 1 else None)
    if not serial or serial not in serials:
        raise SystemExit("Connect, authorize and unlock one Android phone, or pass --serial.")
    adb += ["-s", serial]
    for path in ("app/build/outputs/apk/debug/app-debug.apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"):
        subprocess.run(adb + ["install", "--user", "0", "-r", str(ROOT / path)], check=True)
    subprocess.run(adb + ["shell", "input", "keyevent", "KEYCODE_WAKEUP"], check=True)
    time.sleep(0.5)
    subprocess.run(adb + ["shell", "wm", "dismiss-keyguard"], check=True)
    result = subprocess.check_output(adb + ["shell", "am", "instrument", "--user", "0", "-w", "-r", "-e", "storeAssets", "true", "app.tideline.test/app.tideline.WidgetInstrumentation"], text=True)
    print(result)
    if "INSTRUMENTATION_CODE: -1" not in result or "failure=" in result:
        raise SystemExit("Capture failed. Unlock the phone and check the instrumentation output.")
    for name in NAMES + ["provenance.txt"]:
        data = subprocess.check_output(adb + ["exec-out", "run-as", "app.tideline", "cat", "files/play-assets/" + name])
        destination = ROOT / "publishing/asset-provenance.txt" if name == "provenance.txt" else IMAGES / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
    print(f"Captured {len(NAMES)} images into {IMAGES.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
