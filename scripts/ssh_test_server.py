#!/usr/bin/env python3
"""Run a command against an isolated, public-key-only loopback OpenSSH server."""
import getpass
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time


def main():
    command = sys.argv[1:]
    if command and command[0] == "--":
        command.pop(0)
    if not command:
        raise SystemExit("Usage: ssh_test_server.py -- command [args...]")
    sshd = shutil.which("sshd") or "/usr/sbin/sshd"
    with tempfile.TemporaryDirectory(prefix="shelldeck-sshd-", dir=os.environ.get("TMPDIR", "/tmp")) as folder:
        root = Path(folder)
        for name, algorithm, password in [
            ("host", "ed25519", ""), ("ed25519", "ed25519", ""),
            ("ed25519-encrypted", "ed25519", "test-passphrase"),
            ("rsa", "rsa", ""), ("rsa-encrypted", "rsa", "test-passphrase"),
            ("unauthorized", "ed25519", ""),
        ]:
            subprocess.run(["ssh-keygen", "-q", "-t", algorithm, "-N", password, "-f", str(root / name)], check=True)
        (root / "test-shell").write_text('#!/bin/sh\nif [ -n "$SSH_ORIGINAL_COMMAND" ]; then exec /bin/sh -c "$SSH_ORIGINAL_COMMAND"; else exec /bin/bash --noprofile --norc -i; fi\n')
        (root / "test-shell").chmod(0o700)
        authorized = ["ed25519", "ed25519-encrypted", "rsa", "rsa-encrypted"]
        (root / "authorized_keys").write_text("".join((root / (name + ".pub")).read_text() for name in authorized))
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        privileged = os.environ.get("SSH_TEST_SUDO") == "1"
        (root / "sshd_config").write_text(f"""ListenAddress 127.0.0.1
Port {port}
HostKey {root}/host
PidFile {root}/pid
AuthorizedKeysFile {root}/authorized_keys
StrictModes no
PasswordAuthentication no
KbdInteractiveAuthentication no
AuthenticationMethods publickey
UsePAM {"yes" if privileged else "no"}
AllowUsers {getpass.getuser()}
PermitRootLogin prohibit-password
AllowTcpForwarding no
X11Forwarding no
PermitTunnel no
PrintMotd no
SetEnv LANG=C.UTF-8 LC_ALL=C.UTF-8
ForceCommand {root}/test-shell
LogLevel ERROR
""")
        with (root / "server.log").open("w") as log:
            server = subprocess.Popen((["sudo", "-n"] if privileged else []) + [sshd, "-D", "-e", "-f", str(root / "sshd_config")], stdout=log, stderr=log)
            try:
                for _ in range(100):
                    if server.poll() is not None:
                        raise RuntimeError((root / "server.log").read_text())
                    try:
                        with socket.create_connection(("127.0.0.1", port), timeout=0.1):
                            break
                    except OSError:
                        time.sleep(0.05)
                else:
                    raise RuntimeError("Test SSH server did not start")
                env = os.environ.copy()
                env.update(SSH_TEST_DIR=str(root), SSH_TEST_PORT=str(port), SSH_TEST_USER=getpass.getuser())
                result = subprocess.run(command, env=env)
                return result.returncode
            finally:
                if privileged:
                    subprocess.run(["sudo", "-n", "kill", "-TERM", str(server.pid)], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                else:
                    server.terminate()
                try:
                    server.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    if privileged:
                        subprocess.run(["sudo", "-n", "kill", "-KILL", str(server.pid)], check=False)
                    else:
                        server.kill()
                    server.wait()


if __name__ == "__main__":
    raise SystemExit(main())
