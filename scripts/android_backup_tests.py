#!/usr/bin/env python3
"""Prove encrypted backup portability using two devices and the ephemeral SSH test server.

Usage: python3 scripts/ssh_test_server.py -- python3 scripts/android_backup_tests.py \
    --source emulator-5554 --target emulator-5556
The target must not already have the debug app installed; no user app data is cleared.
"""
import argparse
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True)
    parser.add_argument("--target", required=True)
    args = parser.parse_args()
    if args.source == args.target:
        parser.error("Source and target must be different devices")
    root = Path(os.environ["SSH_TEST_DIR"])
    port = os.environ["SSH_TEST_PORT"]
    app = "cc.cherr.shelldeck.debug"
    adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")

    def command(serial, *arguments, **kwargs):
        kwargs.setdefault("check", True)
        return subprocess.run([adb, "-s", serial, *arguments], **kwargs)

    target = command(args.target, "shell", "pm", "path", app, capture_output=True, text=True, check=False)
    if target.stdout.strip():
        raise SystemExit("Use a clean target device without the ShellDeck debug app installed")
    source_present = bool(command(args.source, "shell", "pm", "path", app, capture_output=True, text=True, check=False).stdout.strip())
    subprocess.run(["./gradlew", ":app:assembleDebug", ":app:assembleDebugAndroidTest", "--console=plain"], check=True)
    installed = []
    try:
        for serial in (args.source, args.target):
            command(serial, "install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
            installed.append(serial)
            command(serial, "install", "-r", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
            command(serial, "reverse", f"tcp:{port}", f"tcp:{port}")
            command(serial, "shell", "run-as", app, "mkdir", "-p", "files")
        with (root / "ed25519").open("rb") as key:
            command(args.source, "shell", "run-as", app, "sh", "-c", "'cat > files/test-ssh-key'", stdin=key)

        def phase(serial, name):
            result = command(serial, "shell", "am", "instrument", "-w", "-r",
                "-e", "class", "cc.cherr.shelldeck.BackupCrossDeviceTest",
                "-e", "backupPhase", name, "-e", "sshPort", port,
                "-e", "sshUser", os.environ["SSH_TEST_USER"],
                app + ".test/androidx.test.runner.AndroidJUnitRunner", capture_output=True, text=True)
            if "OK (1 test)" not in result.stdout:
                print(result.stdout)
                raise RuntimeError(f"Cross-device {name} test failed")
            print(f"Cross-device {name}: passed")

        phase(args.source, "source")
        # Transfer only the password-encrypted archive and a source-device ciphertext negative fixture.
        for filename in ("cross-backup.sdbak", "cross-source-envelope.bin"):
            data = command(args.source, "exec-out", "run-as", app, "cat", "files/" + filename, capture_output=True).stdout
            command(args.target, "shell", "run-as", app, "sh", "-c", "'cat > files/" + filename + "'", input=data)
        phase(args.target, "target")
        print("Target rejected the source Keystore envelope and authenticated using the restored identity.")
    finally:
        for serial in installed:
            subprocess.run([adb, "-s", serial, "reverse", "--remove", f"tcp:{port}"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run([adb, "-s", serial, "shell", "run-as", app, "rm", "-f",
                "files/test-ssh-key", "files/cross-backup.sdbak", "files/cross-source-envelope.bin"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if serial == args.target or not source_present:
                subprocess.run([adb, "-s", serial, "uninstall", app], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                subprocess.run([adb, "-s", serial, "uninstall", app + ".test"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
