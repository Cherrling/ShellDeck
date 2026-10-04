#!/usr/bin/env python3
"""Create/reuse a private local APK signing identity and upload encrypted GitHub Secrets.

Requires JDK keytool and gh with repository Secrets and Variables write permissions.
No private material or passwords are printed. Back up the entire output directory securely.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default="Cherrling/ShellDeck")
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--local-only", action="store_true")
    args = parser.parse_args()
    root = args.directory.expanduser().resolve()
    checkout = Path(__file__).resolve().parents[1]
    if root == checkout or checkout in root.parents:
        raise SystemExit("Signing material must be stored outside the source checkout")
    os.umask(0o077)
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    root.chmod(0o700)
    config = root / "signing.json"
    store = root / "release.p12"
    if config.exists():
        data = json.loads(config.read_text())
        if not store.is_file():
            raise SystemExit("Existing signing configuration has no keystore; restore your backup")
    else:
        if store.exists():
            raise SystemExit("Existing keystore has no configuration; refusing to replace it")
        password = secrets.token_urlsafe(48)
        data = {"alias": "shelldeck", "store_password": password, "key_password": password}
        env = os.environ.copy()
        env["SHELLDECK_SIGNING_PASSWORD"] = password
        subprocess.run(["keytool", "-genkeypair", "-storetype", "PKCS12", "-keystore", str(store),
            "-alias", data["alias"], "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
            "-dname", "CN=ShellDeck, O=Cherrling", "-storepass:env", "SHELLDECK_SIGNING_PASSWORD",
            "-keypass:env", "SHELLDECK_SIGNING_PASSWORD"], env=env, check=True, capture_output=True)
        config.write_text(json.dumps(data, indent=2) + "\n")
    store.chmod(0o600); config.chmod(0o600)
    env = os.environ.copy(); env["SHELLDECK_SIGNING_PASSWORD"] = data["store_password"]
    cert = subprocess.check_output(["keytool", "-exportcert", "-keystore", str(store), "-alias", data["alias"],
        "-storepass:env", "SHELLDECK_SIGNING_PASSWORD"], env=env, stderr=subprocess.DEVNULL)
    fingerprint = hashlib.sha256(cert).hexdigest()
    (root / "certificate-sha256.txt").write_text(fingerprint + "\n")
    if not args.local_only:
        values = {"ANDROID_KEYSTORE_BASE64": base64.b64encode(store.read_bytes()).decode(),
            "ANDROID_KEYSTORE_PASSWORD": data["store_password"], "ANDROID_KEY_ALIAS": data["alias"],
            "ANDROID_KEY_PASSWORD": data["key_password"]}
        for name, value in values.items():
            subprocess.run(["gh", "secret", "set", name, "--repo", args.repo], input=value, text=True, check=True)
        subprocess.run(["gh", "variable", "set", "ANDROID_SIGNING_CERT_SHA256", "--repo", args.repo,
            "--body", fingerprint], check=True)
    print(f"Signing identity preserved in {root}; back up this directory outside GitHub.")
    print(f"Public certificate SHA256: {fingerprint}")


if __name__ == "__main__":
    main()
