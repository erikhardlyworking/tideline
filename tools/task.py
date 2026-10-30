"""Small cross-platform task runner for the app and its real-data checks."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    tasks = ["build", "test", "device-apk", "data"]
    # The development workspace can also contain the separate research campaign.
    if all((ROOT / "tools" / name).is_file() for name in ("validate_kotlin.py", "validate_locations.py")):
        tasks.append("validate")
    parser.add_argument("task", choices=tasks)
    task = parser.parse_args().task
    environment = os.environ.copy()
    if not environment.get("JAVA_HOME") and (ROOT / ".build-tools/jdk/bin/java").exists():
        environment["JAVA_HOME"] = str(ROOT / ".build-tools/jdk")
    environment.setdefault("GRADLE_USER_HOME", str(ROOT / ".gradle-user-home"))
    environment.setdefault("ANDROID_USER_HOME", str(ROOT / ".build-tools/android-user"))
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    def gradle(*arguments):
        subprocess.run([str(wrapper), "--no-daemon", *arguments], cwd=ROOT, env=environment, check=True)
    def python(script):
        executable = ROOT / (".venv/Scripts/python.exe" if os.name == "nt" else ".venv/bin/python")
        subprocess.run([str(executable) if executable.exists() else sys.executable, str(ROOT / "tools" / script)], cwd=ROOT, env=environment, check=True)
    if task == "build":
        gradle(":app:assembleDebug")
    elif task == "test":
        gradle(":tide-core:test", ":app:lintDebug")
    elif task == "device-apk":
        gradle(":app:assembleDebug", ":app:assembleDebugAndroidTest")
    elif task == "data":
        if not (ROOT / "data/source_ap.npy").exists():
            python("download_inputs.py"); python("coastal.py")
        python("build_android_data.py")
    else:
        gradle(":tide-cli:installDist")
        python("validate_kotlin.py"); python("validate_locations.py")


if __name__ == "__main__":
    main()
