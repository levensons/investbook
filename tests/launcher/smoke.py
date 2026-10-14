"""Real JAR lifecycle smoke; no personal data or OpenAI account.

Requires a free port 2030 and a built JAR. Uses a temporary H2 DB. systemd
registration is stubbed because CI containers lack a desktop user session;
the generated unit and desktop file are validated with system tools.
Run: python3 tests/launcher/smoke.py target/investbook-2026.1.jar
"""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("launcher", ROOT / "scripts/linux_launcher.py")
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


def wait_for(predicate, timeout=120):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        if predicate():
            return
        time.sleep(.3)
    raise AssertionError("Timed out waiting for condition")


def main():
    assert not launcher.port_busy(), "Port 2030 must be free; stop your test instance first."
    with tempfile.TemporaryDirectory(prefix="investbook smoke ") as tmp:
        base = Path(tmp)
        env = dict(os.environ)
        for key, folder in (("XDG_CONFIG_HOME", "config"), ("XDG_DATA_HOME", "data"), ("XDG_STATE_HOME", "state")):
            env[key] = str(base / folder)
        launcher.CONFIG = base / "config/investbook-launcher"
        launcher.DATA = base / "data/investbook-launcher"
        launcher.STATE = base / "state/investbook-launcher"
        launcher.private_dir(launcher.STATE)
        # No production background requests in a lifecycle smoke test.
        extra = base / "test.properties"
        extra.write_text("investbook.market-data-auto-update=false\n")
        folder = base / "мой портфель 10% $"
        import argparse
        args = argparse.Namespace(mcp=False, update=False, data_dir=folder, new=True, config=extra,
                                  extensions_dir=None, jar=Path(sys.argv[1]).resolve(), no_desktop=False)
        with patch.object(launcher, "systemctl", return_value=subprocess.CompletedProcess([], 0)), \
             patch.object(launcher, "active", return_value=False):
            launcher.setup(args)
        unit = launcher.CONFIG.parent / "systemd/user" / launcher.SERVICE
        # Verify syntax without launching a user manager in the container.
        result = subprocess.run(["systemd-analyze", "verify", str(unit)], capture_output=True, text=True)
        assert result.returncode == 0, result.stderr
        desktop = launcher.DATA.parent / "applications/investbook-launcher.desktop"
        subprocess.run(["desktop-file-validate", str(desktop)], check=True, capture_output=True)
        config = launcher.read_config()
        keyring = base / "keyring"
        keyring.write_text("#!/bin/sh\nprintf '%s' 'synthetic-tunnel-key'\n")
        keyring.chmod(0o700)
        marker = base / "tunnel-started.json"
        tunnel = base / "tunnel"
        tunnel.write_text(f'''#!{sys.executable}
import hashlib, json, os, time
from pathlib import Path
from urllib.request import Request, urlopen
assert os.environ['CONTROL_PLANE_API_KEY'] == 'synthetic-tunnel-key'
token = os.environ['INVESTBOOK_AGENT_TOKEN']
with urlopen(Request('http://127.0.0.1:2030/api/agent/v1/accounts', headers={{'Authorization': 'Bearer ' + token}})) as r:
    assert r.status == 200
Path({str(marker)!r}).write_text(json.dumps({{'pid': os.getpid(), 'digest': hashlib.sha256(token.encode()).hexdigest()}}))
while True: time.sleep(.3)
''')
        tunnel.chmod(0o700)
        config["mcp"] = {"accounts": {"main": "synthetic"}, "keyring": str(keyring), "profile": "test", "tunnel": str(tunnel)}
        launcher.save_config(config)
        launcher.write_private(launcher.CONFIG / "application.properties", launcher.render_properties(config))
        process = None
        try:
            process = subprocess.Popen([sys.executable, str(launcher.DATA / "launcher.py"), "serve"], env=env)
            wait_for(lambda: marker.exists() or process.poll() is not None or (launcher.STATE / "status.json").exists() and json.loads((launcher.STATE / "status.json").read_text())["mcp"] == "failed")
            assert marker.exists(), "Tunnel fixture failed"
            assert process.poll() is None, "Java or supervisor failed; inspect the temporary application log."
            assert launcher.ready()
            assert (folder / "investbook3.mv.db").exists(), "Configured path must support Unicode, spaces, percent and dollar"
            first = json.loads(marker.read_text())
            assert not launcher.read_config()["allow_create"]
            # A second supervisor cannot spawn another Java process.
            second = subprocess.run([sys.executable, str(launcher.DATA / "launcher.py"), "serve"], env=env, capture_output=True)
            assert second.returncode == 1
            process.terminate(); process.wait(timeout=40)
            assert process.returncode == 0
            wait_for(lambda: not launcher.port_busy(), 10)
            assert not Path(f"/proc/{first['pid']}").exists(), "Tunnel process leaked"
            # Restart: a new ephemeral token must still reach both components.
            marker.unlink()
            process = subprocess.Popen([sys.executable, str(launcher.DATA / "launcher.py"), "serve"], env=env)
            wait_for(lambda: marker.exists() or process.poll() is not None or (launcher.STATE / "status.json").exists() and json.loads((launcher.STATE / "status.json").read_text())["mcp"] == "failed")
            assert marker.exists(), "Tunnel fixture failed"
            assert process.poll() is None
            second = json.loads(marker.read_text())
            assert first["digest"] != second["digest"]
            # A crashing tunnel must not stop Investbook.
            os.kill(second["pid"], 15)
            status = launcher.STATE / "status.json"
            wait_for(lambda: json.loads(status.read_text())["mcp"] == "failed", 10)
            assert process.poll() is None and launcher.ready()
            process.terminate(); process.wait(timeout=40)
            assert process.returncode == 0
            wait_for(lambda: not launcher.port_busy(), 10)
            for path in [*launcher.CONFIG.glob("*"), *launcher.STATE.glob("*")]:
                if path.is_file():
                    assert "synthetic-tunnel-key" not in path.read_text(), "Secret leaked to configuration or logs"
            print("PASS: real JAR/H2 startup, configured data path, authenticated MCP API, repeated-start guard, restart, rotated token, tunnel failure isolation, shutdown and unit/desktop syntax")
        except Exception:
            # Retain private diagnostic files on failure, without printing their contents.
            destination = Path(tempfile.mkdtemp(prefix="investbook-smoke-failure-"))
            shutil.copytree(launcher.STATE, destination / "state", dirs_exist_ok=True)
            print("Diagnostics retained at", destination, file=sys.stderr)
            raise
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                process.wait(timeout=40)


if __name__ == "__main__":
    main()
