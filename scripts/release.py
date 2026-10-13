"""Validate release inputs and the signed APK; uses only Python's standard library."""

from datetime import datetime
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APPLICATION_ID = "cc.cherr.shelldeck"
VERSION_PATTERN = re.compile(
    r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-((?:0|[1-9]\d*|[\dA-Za-z-]*[A-Za-z-][\dA-Za-z-]*)"
    r"(?:\.(?:0|[1-9]\d*|[\dA-Za-z-]*[A-Za-z-][\dA-Za-z-]*))*))?"
)


def read_version(path: Path) -> tuple[str, int]:
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, value = line.split("=", 1)
        if key in values:
            raise ValueError(f"Duplicate version property: {key}")
        values[key] = value
    if set(values) != {"versionName", "versionCode"}:
        raise ValueError("Expected versionName and versionCode only")
    version = values["versionName"]
    if not VERSION_PATTERN.fullmatch(version):
        raise ValueError("Invalid versionName (use SemVer without build metadata)")
    code = values["versionCode"]
    if not re.fullmatch(r"[1-9]\d*", code) or int(code) > 2_100_000_000:
        raise ValueError("Invalid Android versionCode")
    return version, int(code)


def validate_tag(tag: str, version: str) -> None:
    if tag != f"v{version}" or not VERSION_PATTERN.fullmatch(tag.removeprefix("v")):
        raise ValueError("Tag must exactly match v + versionName")


def normalize_fingerprint(value: str) -> str:
    result = value.strip().replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", result):
        raise ValueError("Expected a SHA-256 signing certificate fingerprint")
    return result


def validate_apk_output(badging: str, signatures: str, version: str, code: int, expected: str) -> None:
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    if not package or package.groups() != (APPLICATION_ID, str(code), version):
        raise ValueError("APK package/version does not match release metadata")
    if re.search(r"^application-debuggable(?:\s|$)", badging, re.M):
        raise ValueError("Refusing to publish a debuggable APK")
    fingerprints = re.findall(r"^Signer #\d+ certificate SHA-256 digest: (\S+)", signatures, re.M)
    if len(fingerprints) != 1 or normalize_fingerprint(fingerprints[0]) != normalize_fingerprint(expected):
        raise ValueError("APK signer does not match the configured release certificate")


def validate_font_packaging(apk: Path, edition: str) -> None:
    with zipfile.ZipFile(apk) as archive:
        fonts = sorted(name for name in archive.namelist()
                       if Path(name).suffix.lower() in {".ttf", ".otf", ".ttc", ".woff", ".woff2"})
        expected = "res/font/maple_mono_nf_cn_regular.ttf"
        if edition == "lite":
            if fonts:
                raise ValueError(f"Lite APK must not contain fonts: {fonts}")
        elif edition == "bundled":
            if fonts != [expected]:
                raise ValueError(f"Bundled APK must contain Maple Mono only: {fonts}")
            source = ROOT / "app/src/bundled/res/font/maple_mono_nf_cn_regular.ttf"
            if archive.read(expected) != source.read_bytes():
                raise ValueError("Bundled font differs from source")
        else:
            raise ValueError("Unknown font edition")


def build_metadata(version: str, code: int, timestamp: str, revision: str) -> dict:
    parsed = datetime.strptime(timestamp, "%Y-%m-%d %H:%M:%S UTC+8")
    if parsed.strftime("%Y-%m-%d %H:%M:%S UTC+8") != timestamp or not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Invalid build timestamp or source revision")
    return {"versionName": version, "versionCode": code, "buildTime": timestamp, "sourceRevision": revision}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    validate = sub.add_parser("validate")
    validate.add_argument("tag", nargs="?")
    sub.add_parser("metadata")
    verify = sub.add_parser("verify-apk")
    verify.add_argument("apk", type=Path)
    fonts = sub.add_parser("verify-fonts")
    fonts.add_argument("apk", type=Path)
    fonts.add_argument("edition", choices=("bundled", "lite"))
    args = parser.parse_args()
    if args.command == "verify-fonts":
        validate_font_packaging(args.apk, args.edition)
        print(f"Verified {args.edition} font packaging: {args.apk}")
        return
    version, code = read_version(ROOT / "version.properties")
    if args.command == "metadata":
        print(json.dumps(build_metadata(version, code, os.environ["SHELLDECK_BUILD_TIME"], os.environ["SHELLDECK_SOURCE_REVISION"]), indent=2))
        return
    if args.command == "validate":
        if args.tag is not None:
            validate_tag(args.tag, version)
        print(json.dumps({"versionName": version, "versionCode": code}))
        return
    sdk = Path(os.environ["ANDROID_HOME"])
    tools = sdk / "build-tools" / "35.0.0"
    expected = os.environ["ANDROID_SIGNING_CERT_SHA256"]
    # apksigner must successfully verify the file, not merely print its certificates.
    signatures = subprocess.check_output(
        [str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(args.apk)], text=True,
    )
    badging = subprocess.check_output(
        [str(tools / "aapt2"), "dump", "badging", str(args.apk)], text=True,
    )
    validate_apk_output(badging, signatures, version, code, expected)
    print(f"Verified {APPLICATION_ID} {version} ({code}) and release certificate")


if __name__ == "__main__":
    main()
