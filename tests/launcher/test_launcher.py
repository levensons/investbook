"""Run: python3 -m unittest discover -s tests/launcher -v"""
import argparse
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("launcher", Path(__file__).resolve().parents[2] / "scripts/linux_launcher.py")
launcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(launcher)


class LauncherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="investbook launcher ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name, value in (("CONFIG", self.root / "config/investbook-launcher"),
                            ("DATA", self.root / "data/investbook-launcher"),
                            ("STATE", self.root / "state/investbook-launcher")):
            p = patch.object(launcher, name, value); p.start(); self.addCleanup(p.stop)
        self.output = contextlib.redirect_stdout(io.StringIO()); self.output.__enter__()
        self.addCleanup(self.output.__exit__, None, None, None)

    def args(self, **kwargs):
        defaults = dict(mcp=False, update=False, data_dir=None, new=False, config=None,
                        extensions_dir=None, jar=None, no_desktop=False)
        return argparse.Namespace(**(defaults | kwargs))

    def jar(self, name="app.jar"):
        file = self.root / name
        with zipfile.ZipFile(file, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Main-Class: org.springframework.boot.loader.launch.PropertiesLauncher\nStart-Class: ru.investbook.Application\n")
        return file

    def existing_db(self):
        folder = self.root / "мой портфель $ 10%"
        folder.mkdir()
        (folder / "investbook3.mv.db").write_bytes(b"existing portfolio")
        return folder

    def test_missing_database_cannot_silently_create_empty_portfolio(self):
        with self.assertRaisesRegex(launcher.LaunchError, "нет investbook3"):
            launcher.validate_data_dir(self.root / "wrong")
        self.assertFalse((self.root / "wrong").exists())

    def test_new_requires_empty_directory(self):
        with self.assertRaisesRegex(launcher.LaunchError, "пустую"):
            launcher.validate_data_dir(self.existing_db(), new=True)
        self.assertEqual(self.root / "new", launcher.validate_data_dir(self.root / "new", new=True))

    def test_backup_preserves_source_and_refuses_open_database(self):
        folder = self.existing_db()
        db = folder / "investbook3.mv.db"
        with db.open("rb"):
            with self.assertRaisesRegex(launcher.LaunchError, "открыта"):
                launcher.backup_database(folder)
        launcher.backup_database(folder)
        backups = list((folder / "launcher-backups").glob("*.mv.db"))
        self.assertEqual(1, len(backups))
        self.assertEqual(db.read_bytes(), backups[0].read_bytes())
        self.assertEqual(0o600, backups[0].stat().st_mode & 0o777)

    @patch.object(launcher, "tool", return_value="systemctl")
    @patch.object(launcher, "systemctl")
    @patch.object(launcher, "check_stopped")
    @patch.object(launcher, "java_path", return_value="/usr/bin/java")
    def test_setup_and_update_keep_existing_database_and_config(self, *_):
        folder = self.existing_db()
        extra = self.root / "old.properties"; extra.write_text("spring.datasource.username=sa\n")
        launcher.setup(self.args(data_dir=folder, jar=self.jar(), config=extra))
        before = launcher.read_config()
        self.assertEqual(str(folder), before["data_dir"])
        self.assertFalse(before["allow_create"])
        self.assertEqual(str(extra), before["extra_config"])
        self.assertTrue((launcher.DATA / "app.jar").exists())
        self.assertEqual(b"existing portfolio", (folder / "investbook3.mv.db").read_bytes())
        unit = (launcher.CONFIG.parent / "systemd/user" / launcher.SERVICE).read_text()
        self.assertIn("KillMode=control-group", unit)
        self.assertIn("XDG_CONFIG_HOME=", unit)
        self.assertTrue((launcher.DATA.parent / "applications/investbook-launcher.desktop").exists())
        with patch.object(launcher, "build_jar", side_effect=AssertionError("must not rebuild")):
            launcher.setup(self.args())
        launcher.setup(self.args(update=True, jar=self.jar("updated.jar")))
        self.assertEqual(before, launcher.read_config())
        self.assertEqual(2, len(list((folder / "launcher-backups").glob("*.mv.db"))))

    @patch.object(launcher, "tool", return_value="systemctl")
    @patch.object(launcher, "systemctl")
    @patch.object(launcher, "check_stopped")
    @patch.object(launcher, "java_path", return_value="/usr/bin/java")
    def test_failed_update_does_not_replace_installed_version(self, *_):
        folder = self.existing_db()
        launcher.setup(self.args(data_dir=folder, jar=self.jar()))
        before = (launcher.DATA / "app.jar").read_bytes()
        with self.assertRaises(launcher.LaunchError):
            launcher.setup(self.args(update=True, jar=self.root / "missing.jar"))
        self.assertEqual(before, (launcher.DATA / "app.jar").read_bytes())
        (folder / "investbook3.mv.db").unlink()
        with self.assertRaisesRegex(launcher.LaunchError, "нет investbook3"):
            launcher.setup(self.args(update=True, jar=self.jar()))
        self.assertFalse((folder / "investbook3.mv.db").exists())

    def test_properties_escape_account_ids_without_injecting_settings(self):
        config = {"data_dir": "/tmp/мой портфель", "mcp": {"accounts": {"main": "123\ninvestbook.agent.enabled=false"}}}
        result = launcher.render_properties(config)
        self.assertIn("investbook.agent.enabled=true\n", result)
        self.assertIn("main=123\\ninvestbook.agent.enabled\\=false\n", result)
        self.assertNotIn("TOKEN", result)
        self.assertIn("server.address=127.0.0.1", result)
        self.assertIn("\\u043c", result)

    @patch.object(launcher, "active", return_value=True)
    @patch.object(launcher, "wait_ready", return_value=True)
    @patch.object(launcher, "status")
    @patch.object(launcher, "systemctl")
    def test_repeat_start_does_not_restart_or_rebuild(self, ctl, *_):
        launcher.save_config({"data_dir": "/tmp/test"})
        launcher.start(open_browser=False)
        ctl.assert_not_called()

    @patch.object(launcher, "active", return_value=False)
    @patch.object(launcher, "port_busy", return_value=True)
    @patch.object(launcher, "systemctl")
    def test_occupied_port_does_not_stop_or_adopt_another_app(self, ctl, *_):
        launcher.save_config({"data_dir": "/tmp/test"})
        with self.assertRaisesRegex(launcher.LaunchError, "другим экземпляром"):
            launcher.start(open_browser=False)
        ctl.assert_not_called()

    @patch.object(launcher.subprocess, "run", side_effect=subprocess.TimeoutExpired("secret-tool", 10))
    def test_locked_keyring_degrades_without_stopping_java(self, _):
        launcher.private_dir(launcher.STATE)
        result = launcher.start_tunnel({"mcp": {"keyring": "secret-tool", "profile": "test"}}, {})
        self.assertIsNone(result)
        self.assertEqual("failed", json.loads((launcher.STATE / "status.json").read_text())["mcp"])

    def test_existing_portfolio_missing_at_runtime_is_not_recreated(self):
        launcher.save_config({"data_dir": str(self.root / "missing"), "allow_create": False})
        with self.assertRaisesRegex(launcher.LaunchError, "нет investbook3"):
            launcher.serve()
        self.assertFalse((self.root / "missing").exists())

    def test_shell_wrappers_forward_arguments_from_another_directory(self):
        scripts = self.root / "bin"; scripts.mkdir()
        executable = scripts / "python3"
        import sys
        executable.write_text(f"#!{sys.executable}\nimport json,sys\nprint(json.dumps(sys.argv[1:]))\n")
        executable.chmod(0o700)
        env = dict(os.environ, PATH=str(scripts) + os.pathsep + os.environ["PATH"])
        for wrapper, arguments, expected in (("run.sh", [], ["start"]),
                                             ("run.sh", ["start", "--no-browser"], ["start", "--no-browser"]),
                                             ("setup.sh", ["--data-dir", "path with spaces"], ["setup", "--data-dir", "path with spaces"])):
            result = subprocess.run(["bash", str(launcher.ROOT / wrapper), *arguments], cwd=self.root,
                                    env=env, text=True, capture_output=True, check=True)
            self.assertEqual(expected, json.loads(result.stdout)[1:])

    def test_failed_keyring_setup_preserves_working_mcp_configuration(self):
        config = {"data_dir": str(self.root / "portfolio"), "mcp": {"profile": "original"}}
        launcher.save_config(config)
        answers = iter(["tunnel_test", "main", "private-account", ""])
        def run(command, **kwargs):
            return subprocess.CompletedProcess(command, 1 if "store" in command else 0)
        with patch.object(launcher.sys.stdin, "isatty", return_value=True), \
             patch.object(launcher, "tool", side_effect=lambda name, hint: name), \
             patch("builtins.input", side_effect=lambda _: next(answers)), \
             patch.object(launcher.getpass, "getpass", return_value="synthetic-secret"), \
             patch.object(launcher.subprocess, "run", side_effect=run):
            with self.assertRaisesRegex(launcher.LaunchError, "связке ключей"):
                launcher.setup_mcp(config)
        self.assertEqual({"profile": "original"}, launcher.read_config()["mcp"])
        self.assertNotIn("synthetic-secret", (launcher.CONFIG / "config.json").read_text())

    def test_mcp_key_is_passed_only_in_child_environment(self):
        launcher.private_dir(launcher.STATE)
        config = {"mcp": {"keyring": "secret-tool", "profile": "profile", "tunnel": "/bin/tunnel"}}
        with patch.object(launcher.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "test-secret\n")), \
             patch.object(launcher.subprocess, "Popen") as popen:
            launcher.start_tunnel(config, {"INVESTBOOK_AGENT_TOKEN": "local-token"})
            args, kwargs = popen.call_args
            self.assertNotIn("test-secret", repr(args))
            self.assertEqual("test-secret", kwargs["env"]["CONTROL_PLANE_API_KEY"])
            self.assertEqual("local-token", kwargs["env"]["INVESTBOOK_AGENT_TOKEN"])
        for file in launcher.STATE.iterdir():
            self.assertNotIn("test-secret", file.read_text())


if __name__ == "__main__":
    unittest.main()
