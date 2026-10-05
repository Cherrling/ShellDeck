import tempfile
from pathlib import Path
import unittest

from release import read_version, validate_tag, validate_apk_output, build_metadata


class ReleaseValidationTest(unittest.TestCase):
    def test_build_metadata_rejects_ambiguous_dates_and_revisions(self):
        info = build_metadata("0.1.0", 13, "2026-10-05 15:00:00 UTC", "ab" * 20)
        self.assertEqual(info["versionCode"], 13)
        self.assertEqual(info["buildTime"], "2026-10-05 15:00:00 UTC")
        for date, revision in (("2026-10-05 15:00:00", "ab" * 20), ("2026-02-30 15:00:00 UTC", "ab" * 20),
                               ("2026-10-05 15:00:00 UTC", "invalid")):
            with self.assertRaises(ValueError):
                build_metadata("0.1.0", 13, date, revision)

    def test_tag_must_match_and_rejects_shell_payloads(self):
        validate_tag("v0.1.0-alpha.1", "0.1.0-alpha.1")
        for tag in ("v0.1.0", "0.1.0-alpha.1", "v0.1.0;echo bad", "v0.1.0\n"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                validate_tag(tag, "0.1.0-alpha.1")

    def test_version_metadata_rejects_ambiguous_or_invalid_values(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "version.properties"
            path.write_text("versionName=1.2.3-rc.1\nversionCode=42\n")
            self.assertEqual(read_version(path), ("1.2.3-rc.1", 42))
            for text in (
                "versionName=01.2.3\nversionCode=1\n",
                "versionName=1.2.3-01\nversionCode=1\n",
                "versionName=1.2.3\nversionCode=0\n",
                "versionName=1.2.3\nversionCode=2100000001\n",
                "versionName=1.2.3\nversionCode=1\nversionCode=2\n",
            ):
                path.write_text(text)
                with self.subTest(text=text), self.assertRaises(ValueError):
                    read_version(path)

    def test_apk_gate_rejects_wrong_identity_version_debug_and_signer(self):
        badging = "package: name='cc.cherr.shelldeck' versionCode='1' versionName='0.1.0'\n"
        signer = f"Signer #1 certificate SHA-256 digest: {'ab' * 32}\n"
        validate_apk_output(badging, signer, "0.1.0", 1, "AB:" * 31 + "AB")
        for bad, sig in (
            (badging.replace("cc.cherr.shelldeck", "cc.cherr.shelldeck.debug"), signer),
            (badging.replace("versionCode='1'", "versionCode='2'"), signer),
            (badging.replace("versionName='0.1.0'", "versionName='0.2.0'"), signer),
            (badging + "application-debuggable\n", signer),
            (badging, signer.replace("ab", "cd")),
            (badging, ""),
            (badging, signer + signer.replace("#1", "#2")),
        ):
            with self.subTest(bad=bad, sig=sig), self.assertRaises(ValueError):
                validate_apk_output(bad, sig, "0.1.0", 1, "ab" * 32)


if __name__ == "__main__":
    unittest.main()
