"""Java provisioning regressions: trusted download, reuse and Maven selection."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import unittest
from unittest.mock import patch
import urllib.error
import zipfile

import test_launcher as helpers

launcher = helpers.launcher

DOWNLOAD = "https://github.com/adoptium/temurin26-binaries/releases/download/test/jdk.tar.gz"


class Download(io.BytesIO):
    def geturl(self):
        return DOWNLOAD


class JavaSetupTest(unittest.TestCase):
    setUp = helpers.LauncherTest.setUp

    def archive(self, unsafe=False):
        content = io.BytesIO()
        with tarfile.open(fileobj=content, mode="w:gz") as archive:
            for name, script in (("java", '#!/bin/sh\necho \'openjdk version "26.0.2"\' >&2\n'),
                                 ("javac", "#!/bin/sh\necho 'javac 26.0.2'\n")):
                data = script.encode()
                info = tarfile.TarInfo("jdk-test/bin/" + name)
                info.mode = 0o755; info.size = len(data)
                archive.addfile(info, io.BytesIO(data))
            if unsafe:
                info = tarfile.TarInfo("jdk-test/escape")
                info.type = tarfile.SYMTYPE
                info.linkname = str(self.root / "outside")
                archive.addfile(info)
        return content.getvalue()

    def responses(self, content, checksum=None):
        assets = [{"binary": {"package": {"link": DOWNLOAD, "checksum": checksum or hashlib.sha256(content).hexdigest()}},
                   "version": {"semver": "26.0.2"}}]
        return [io.BytesIO(json.dumps(assets).encode()), Download(content)]

    def test_download_is_verified_and_cached_for_offline_reuse(self):
        content = self.archive()
        with patch.object(launcher.platform, "machine", return_value="x86_64"), \
             patch.object(launcher.urllib.request, "urlopen", side_effect=self.responses(content)) as download:
            java = launcher.download_jdk()
            self.assertEqual(2, download.call_count)
        self.assertTrue(launcher.is_jdk26(Path(java)))
        source = json.loads((launcher.DATA / "jdk-26/investbook-source.json").read_text())
        self.assertEqual(hashlib.sha256(content).hexdigest(), source["sha256"])
        with patch.dict(os.environ, {"JAVA_HOME": "/missing/java21"}), \
             patch.object(launcher, "download_jdk", side_effect=AssertionError("no network expected")):
            self.assertEqual(java, launcher.java_path())
        self.assertEqual([], list(launcher.DATA.glob("jdk-download-*")))

    def test_checksum_failure_preserves_existing_installation_and_never_executes_archive(self):
        previous = launcher.DATA / "jdk-26/keep.txt"
        previous.parent.mkdir(parents=True)
        previous.write_text("existing Java")
        with patch.object(launcher.platform, "machine", return_value="aarch64"), \
             patch.object(launcher.urllib.request, "urlopen", side_effect=self.responses(self.archive(), "0" * 64)), \
             patch.object(launcher, "is_jdk26", side_effect=AssertionError("unverified code must not execute")):
            with self.assertRaisesRegex(launcher.LaunchError, "сумма"):
                launcher.download_jdk()
        self.assertEqual("existing Java", previous.read_text())
        self.assertEqual([], list(launcher.DATA.glob("jdk-download-*")))

    def test_archive_cannot_escape_staging_directory(self):
        with patch.object(launcher.platform, "machine", return_value="x86_64"), \
             patch.object(launcher.urllib.request, "urlopen", side_effect=self.responses(self.archive(unsafe=True))), \
             patch.object(launcher, "is_jdk26", side_effect=AssertionError("unsafe archive must not execute")):
            with self.assertRaisesRegex(launcher.LaunchError, "распаковать"):
                launcher.download_jdk()
        self.assertFalse((self.root / "outside").exists())
        self.assertFalse((launcher.DATA / "jdk-26").exists())

    def test_unsupported_architecture_does_not_download(self):
        with patch.object(launcher.platform, "machine", return_value="unknown"), \
             patch.object(launcher.urllib.request, "urlopen") as network:
            with self.assertRaisesRegex(launcher.LaunchError, "ARM64"):
                launcher.download_jdk()
            network.assert_not_called()

    def test_network_failure_gives_retry_instructions(self):
        with patch.object(launcher.platform, "machine", return_value="x86_64"), \
             patch.object(launcher.urllib.request, "urlopen", side_effect=urllib.error.URLError("offline")):
            with self.assertRaisesRegex(launcher.LaunchError, "повторите ./setup.sh"):
                launcher.download_jdk()
        self.assertFalse((launcher.DATA / "jdk-26").exists())

    def test_java26_without_compiler_is_not_a_jdk(self):
        java = self.root / "bin/java"
        java.parent.mkdir()
        java.write_text('#!/bin/sh\necho \'openjdk version "26.0.2"\' >&2\n')
        java.chmod(0o755)
        self.assertFalse(launcher.is_jdk26(java))

    def test_maven_gets_selected_jdk_even_when_shell_uses_java21(self):
        target = self.root / "target"; target.mkdir()
        jar = target / "investbook-test.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Main-Class: Launcher\nStart-Class: ru.investbook.Application\n")
        selected = self.root / "jdk-26/bin/java"
        with patch.object(launcher, "ROOT", self.root), \
             patch.dict(os.environ, {"JAVA_HOME": "/java21", "PATH": "/java21/bin:/usr/bin"}), \
             patch.object(launcher.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as run:
            self.assertEqual(jar, launcher.build_jar(argparse.Namespace(jar=None), str(selected)))
            env = run.call_args.kwargs["env"]
            self.assertEqual(str(selected.parent.parent), env["JAVA_HOME"])
            self.assertTrue(env["PATH"].startswith(str(selected.parent) + os.pathsep))
            self.assertEqual("/java21", os.environ["JAVA_HOME"])


if __name__ == "__main__":
    unittest.main()
