#!/usr/bin/env python3
"""Run device tests against the ephemeral server from ssh_test_server.py (debug APK only)."""
import argparse
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    root = Path(os.environ["SSH_TEST_DIR"])
    port = os.environ["SSH_TEST_PORT"]
    adb = [str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"), "-s", args.serial]
    app = "cc.cherr.shelldeck.debug"
    subprocess.run(["./gradlew", ":app:installDebug", ":app:assembleDebugAndroidTest", "--console=plain"], check=True)
    subprocess.run(adb + ["reverse", f"tcp:{port}", f"tcp:{port}"], check=True)
    try:
        subprocess.run(adb + ["shell", "run-as", app, "mkdir", "-p", "files"], check=True)
        with (root / "ed25519").open("rb") as key:
            subprocess.run(adb + ["shell", "run-as", app, "sh", "-c", "'cat > files/test-ssh-key'"], stdin=key, check=True)
        sdk = int(subprocess.check_output(adb + ["shell", "getprop", "ro.build.version.sdk"], text=True).strip())
        if sdk >= 33:
            # Revoke before instrumentation: revocation during a test can kill its process.
            subprocess.run(adb + ["shell", "pm", "revoke", app, "android.permission.POST_NOTIFICATIONS"], check=True)
        result = subprocess.run(["./gradlew", ":app:connectedDebugAndroidTest", "--console=plain",
            f"-Pandroid.testInstrumentationRunnerArguments.sshPort={port}",
            f"-Pandroid.testInstrumentationRunnerArguments.sshUser={os.environ['SSH_TEST_USER']}"])
        return result.returncode
    finally:
        subprocess.run(adb + ["shell", "run-as", app, "rm", "-f", "files/test-ssh-key"], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(adb + ["reverse", "--remove", f"tcp:{port}"], check=False)


if __name__ == "__main__":
    raise SystemExit(main())
