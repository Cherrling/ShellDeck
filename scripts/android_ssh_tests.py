#!/usr/bin/env python3
"""Run device tests against the ephemeral server from ssh_test_server.py (debug APK only)."""
import argparse
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--test-class", help="Optional instrumentation class filter")
    parser.add_argument("--mosh-mode", choices=["direct", "missing", "roaming"], help="Enable Mosh fixture tests (emulator 10.0.2.2 UDP route)")
    args = parser.parse_args()
    # Gradle also needs the selected serial when multiple emulators are connected.
    os.environ["ANDROID_SERIAL"] = args.serial
    root = Path(os.environ["SSH_TEST_DIR"])
    port = os.environ["SSH_TEST_PORT"]
    adb = [str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"), "-s", args.serial]
    app = "cc.cherr.shelldeck.debug"
    (root / "device-files").mkdir(exist_ok=True)
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
        mosh_options = []
        if args.mosh_mode:
            mosh_options = [f"-Pandroid.testInstrumentationRunnerArguments.moshPort={port}"]
            if args.mosh_mode == "missing":
                mosh_options += ["-Pandroid.testInstrumentationRunnerArguments.moshMissing=true"]
            if args.mosh_mode == "roaming":
                if os.environ.get("MOSH_TEST_ROAMING") != "1":
                    raise ValueError("Use scripts/mosh_test_server.py for the roaming fixture")
                mosh_options += ["-Pandroid.testInstrumentationRunnerArguments.moshRoaming=true"]
        result = subprocess.run(["./gradlew", ":app:connectedDebugAndroidTest", "--console=plain",
            f"-Pandroid.testInstrumentationRunnerArguments.sshPort={port}",
            f"-Pandroid.testInstrumentationRunnerArguments.sshUser={os.environ['SSH_TEST_USER']}",
            f"-Pandroid.testInstrumentationRunnerArguments.sftpDirectory={root}/device-files"] +
            ([f"-Pandroid.testInstrumentationRunnerArguments.class={args.test_class}"] if args.test_class else []) + mosh_options)
        return result.returncode
    finally:
        subprocess.run(adb + ["shell", "run-as", app, "rm", "-f", "files/test-ssh-key"], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(adb + ["reverse", "--remove", f"tcp:{port}"], check=False)


if __name__ == "__main__":
    raise SystemExit(main())
