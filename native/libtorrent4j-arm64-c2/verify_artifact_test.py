#!/usr/bin/env python3

import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
VERIFIER = Path(__file__).with_name("verify_artifact.py")
APPROVED_JAR = ROOT / "app/libs/libtorrent4j-android-arm64-2.1.0-39-cp1.5b-c2.jar"


class VerifyArtifactTest(unittest.TestCase):

    def run_verifier(self, *arguments: str | Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["python3", str(VERIFIER), *(str(argument) for argument in arguments)],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def test_approved_artifact_passes(self) -> None:
        result = self.run_verifier("--jar", APPROVED_JAR)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_missing_artifact_fails_closed(self) -> None:
        result = self.run_verifier(
            "--jar", Path("/private/tmp/cp1-5b-intentionally-missing.jar")
        )
        self.assertNotEqual(0, result.returncode)

    def test_changed_jar_fails_closed_before_packaging(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            changed = Path(directory) / "changed.jar"
            changed.write_bytes(APPROVED_JAR.read_bytes() + b"changed")
            result = self.run_verifier("--jar", changed)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("JAR SHA-256 mismatch", result.stderr)

    def test_packaged_arm64_split_and_universal_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk_paths = []
            with zipfile.ZipFile(APPROVED_JAR) as archive:
                elf = archive.read("lib/arm64-v8a/libtorrent4j.so")
            for name in ("app-google-arm64-v8a-alpha.apk", "app-google-universal-alpha.apk"):
                apk = Path(directory) / name
                with zipfile.ZipFile(apk, "w") as archive:
                    archive.writestr("lib/arm64-v8a/libtorrent4j.so", elf)
                apk_paths.append(apk)
            result = self.run_verifier("--apk", *apk_paths)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_missing_universal_apk_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app-google-arm64-v8a-alpha.apk"
            with zipfile.ZipFile(APPROVED_JAR) as archive:
                elf = archive.read("lib/arm64-v8a/libtorrent4j.so")
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/arm64-v8a/libtorrent4j.so", elf)
            result = self.run_verifier("--apk", apk)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("required packaged arm64 output(s) missing: universal", result.stderr)

    def test_arm64_named_apk_without_arm64_elf_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            arm64_apk = Path(directory) / "app-google-arm64-v8a-alpha.apk"
            universal_apk = Path(directory) / "app-google-universal-alpha.apk"
            with zipfile.ZipFile(arm64_apk, "w") as archive:
                archive.writestr("not-the-approved-entry", b"wrong")
            with zipfile.ZipFile(APPROVED_JAR) as archive:
                elf = archive.read("lib/arm64-v8a/libtorrent4j.so")
            with zipfile.ZipFile(universal_apk, "w") as archive:
                archive.writestr("lib/arm64-v8a/libtorrent4j.so", elf)
            result = self.run_verifier("--apk", arm64_apk, universal_apk)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("expected arm64 native entry is missing", result.stderr)


if __name__ == "__main__":
    unittest.main()
