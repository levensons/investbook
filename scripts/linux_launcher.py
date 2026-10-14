#!/usr/bin/env python3
"""Linux desktop launcher. Standard library only; secrets stay in the OS keyring.

Copyright (C) 2026 Spacious Team. SPDX-License-Identifier: AGPL-3.0-or-later
"""
from __future__ import annotations

import argparse
import fcntl
import getpass
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

SERVICE = "investbook-launcher.service"
URL = "http://127.0.0.1:2030"
ROOT = Path(__file__).resolve().parent.parent
CONFIG = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "investbook-launcher"
DATA = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share")) / "investbook-launcher"
STATE = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local/state")) / "investbook-launcher"
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class LaunchError(Exception):
    pass


def fail(message):
    raise LaunchError(message)


def private_dir(path):
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.chmod(0o700)


def write_private(path, content):
    private_dir(path.parent)
    temp = path.with_name(path.name + ".tmp")
    with temp.open("w", encoding="utf-8") as file:
        file.write(content)
    temp.chmod(0o600)
    temp.replace(path)


def save_config(config):
    write_private(CONFIG / "config.json", json.dumps(config, ensure_ascii=False, indent=2) + "\n")


def read_config():
    try:
        return json.loads((CONFIG / "config.json").read_text())
    except FileNotFoundError:
        fail("Сначала выполните ./setup.sh из папки проекта.")
    except (ValueError, OSError):
        fail(f"Не удалось прочитать настройки: {CONFIG / 'config.json'}")


def tool(name, hint):
    result = shutil.which(name)
    if not result:
        fail(f"Не найдена команда {name}. {hint}")
    return result


def systemctl(*args, check=True):
    result = subprocess.run(["systemctl", "--user", *args], capture_output=True, text=True)
    if check and result.returncode:
        fail("Пользовательская служба недоступна. Запускайте от своего пользователя в сеансе Linux "
             "с systemd, без sudo. Диагностика: systemctl --user status " + SERVICE)
    return result


def active():
    return systemctl("is-active", "--quiet", SERVICE, check=False).returncode == 0


def port_busy():
    with socket.socket() as sock:
        return sock.connect_ex(("127.0.0.1", 2030)) == 0


def ready():
    try:
        with HTTP.open(URL + "/actuator/health", timeout=2) as response:
            return json.load(response).get("status") == "UP"
    except (OSError, ValueError, urllib.error.URLError):
        return False


def wait_ready(alive, timeout=120):
    until = time.monotonic() + timeout
    while time.monotonic() < until and alive():
        if ready():
            return True
        time.sleep(0.5)
    return False


def check_stopped():
    if active() or port_busy():
        fail("Сначала завершите работающий Investbook (для этого запуска: ./run.sh stop). "
             "Настройка и обновление не выполняются при открытой базе.")


def java_path():
    candidate = Path(os.environ["JAVA_HOME"]) / "bin/java" if os.environ.get("JAVA_HOME") else Path(tool("java", "Установите JDK 26."))
    result = subprocess.run([str(candidate), "-version"], capture_output=True, text=True)
    match = re.search(r'version "(\d+)', result.stderr + result.stdout)
    if result.returncode or not match or int(match[1]) != 26:
        fail("Нужна Java 26. Установите JDK 26 и задайте JAVA_HOME перед ./setup.sh.")
    return str(candidate.resolve())


def properties(value):
    # Java .properties uses Latin-1 and backslash escapes, including paths/accounts.
    result = ""
    for char in str(value):
        if char in "\\:=#! ":
            result += "\\" + char
        elif char in "\r\n\t":
            result += {"\r": "\\r", "\n": "\\n", "\t": "\\t"}[char]
        elif ord(char) > 126:
            encoded = char.encode("utf-16-be")
            result += "".join("\\u" + encoded[i:i+2].hex() for i in range(0, len(encoded), 2))
        else:
            result += char
    return result


def render_properties(config):
    folder = Path(config["data_dir"])
    values = {
        "server.address": "127.0.0.1", "server.port": "2030",
        "server.forward-headers-strategy": "none",
        "spring.datasource.url": f"jdbc:h2:file:{folder}/investbook3;mode=mysql;non_keywords=value",
        "investbook.data-path": folder,
        "investbook.report-backup-path": folder / "report-backups",
        "logging.file.name": STATE / "investbook.log",
        "investbook.open-home-page-after-start": "false",
        "spring.h2.console.settings.web-allow-others": "false",
        "investbook.agent.enabled": str(bool(config.get("mcp"))).lower(),
    }
    for alias, account in config.get("mcp", {}).get("accounts", {}).items():
        values[f"investbook.agent.accounts.{alias}"] = account
    return "\n".join(f"{key}={properties(value)}" for key, value in values.items()) + "\n"


def validate_data_dir(path, new=False):
    path = path.expanduser().resolve()
    if any(c in str(path) for c in ";\n\r"):
        fail("Путь к базе не должен содержать точку с запятой или перевод строки.")
    db = path / "investbook3.mv.db"
    if not db.is_file() and not new:
        fail(f"В {path} нет investbook3.mv.db. Укажите папку существующей базы через --data-dir. "
             "Для намеренно нового портфеля используйте --new.")
    if new and path.exists() and any(path.iterdir()):
        fail("--new требует пустую папку, чтобы не использовать существующие данные случайно.")
    return path


def backup_database(folder):
    db = folder / "investbook3.mv.db"
    if not db.exists():
        return
    # Detect a desktop app using another port too; do not copy a live H2 database.
    for fd in Path("/proc").glob("[0-9]*/fd/*"):
        try:
            if fd.resolve() == db.resolve():
                fail("База открыта другим процессом. Завершите прежний Investbook перед настройкой.")
        except (OSError, RuntimeError):
            continue
    backups = folder / "launcher-backups"
    private_dir(backups)
    target = backups / (time.strftime("%Y%m%d-%H%M%S") + "-" + secrets.token_hex(3) + ".mv.db")
    shutil.copy2(db, target)
    target.chmod(0o600)
    print(f"Резервная копия базы: {target}")


def unit_quote(value, expand_environment=True):
    # systemd specifier/variable expansion differs from shell quoting.
    escaped = str(value).replace("\\", "\\\\").replace('"', '\\"').replace("%", "%%")
    if expand_environment:
        escaped = escaped.replace("$", "$$")
    return '"' + escaped + '"'


def desktop_quote(value):
    escaped = str(value).replace("\\", "\\\\").replace('"', '\\"').replace("`", "\\`").replace("$", "\\$").replace("%", "%%")
    return '"' + escaped.replace("\\", "\\\\") + '"'


def install_entrypoints(config, desktop=True):
    private_dir(DATA)
    shutil.copy2(Path(__file__), DATA / "launcher.py")
    python = str(Path(sys.executable).resolve())
    unit_dir = CONFIG.parent / "systemd/user"
    unit_dir.mkdir(parents=True, exist_ok=True)
    unit = unit_dir / SERVICE
    unit.write_text("[Unit]\nDescription=Investbook desktop and optional MCP tunnel\n\n[Service]\nType=simple\n"
                    f"ExecStart={unit_quote(python)} {unit_quote(DATA / 'launcher.py')} serve\n"
                    f"WorkingDirectory={str(DATA).replace(chr(37), chr(37) * 2)}\n"
                    + "".join(f"Environment={unit_quote(name + '=' + str(path.parent), False)}\n"
                              for name, path in (("XDG_CONFIG_HOME", CONFIG), ("XDG_DATA_HOME", DATA), ("XDG_STATE_HOME", STATE))) +
                    "UMask=0077\nKillMode=control-group\nTimeoutStopSec=40\nRestart=no\n")
    systemctl("daemon-reload")
    if desktop:
        applications = DATA.parent / "applications"
        applications.mkdir(parents=True, exist_ok=True)
        (applications / "investbook-launcher.desktop").write_text(
            "[Desktop Entry]\nType=Application\nName=Investbook\nComment=Портфель и подключение к ChatGPT\n"
            f"Exec={desktop_quote(python)} {desktop_quote(DATA / 'launcher.py')} start --desktop\n"
            "Icon=accessories-calculator\nTerminal=false\nCategories=Office;Finance;\n")


def build_jar(args):
    if args.jar:
        jar = args.jar.expanduser().resolve()
    else:
        print("Собираю приложение. Это требуется только при установке и явном обновлении.", flush=True)
        result = subprocess.run(["bash", str(ROOT / "mvnw"), "--batch-mode", "-DskipTests",
                                 "-Dno-installer", "-Dno-zip", "package"], cwd=ROOT)
        if result.returncode:
            fail("Сборка не удалась. Установленная версия не заменена; причина приведена выше.")
        jars = list((ROOT / "target").glob("investbook-*.jar"))
        if len(jars) != 1:
            fail("Не найден единственный JAR приложения. Выполните ./setup.sh --jar /путь/к/investbook.jar.")
        jar = jars[0]
    try:
        with zipfile.ZipFile(jar) as archive:
            manifest = archive.read("META-INF/MANIFEST.MF").decode()
            if "Start-Class: ru.investbook." not in manifest or "Main-Class:" not in manifest:
                fail("Указан не исполняемый JAR Investbook.")
    except (OSError, zipfile.BadZipFile, KeyError):
        fail("Не удалось прочитать исполняемый JAR Investbook.")
    return jar


def setup(args):
    tool("systemctl", "Нужен Linux с systemd и пользовательским сеансом.")
    systemctl("show-environment")  # Captured; never print the user's environment.
    existing = (CONFIG / "config.json").exists()
    if args.mcp:
        if not existing:
            fail("Сначала выполните обычный ./setup.sh, затем ./setup.sh --mcp.")
        check_stopped()
        setup_mcp(read_config())
        return
    if existing and not args.update:
        print("Уже настроено. Запуск: ./run.sh. Обновление: ./setup.sh --update. MCP: ./setup.sh --mcp.")
        return
    check_stopped()
    config = read_config() if existing else {}
    if existing and any([args.data_dir, args.new, args.config, args.extensions_dir]):
        fail("Обновление сохраняет настройки базы и расширений. Не передавайте параметры смены базы.")
    if not existing:
        folder = args.data_dir
        if folder is None:
            if not sys.stdin.isatty():
                fail("Укажите --data-dir /путь/к/папке/с/базой (для пустой базы добавьте --new).")
            answer = input(f"Папка существующей базы [{Path.home() / 'investbook'}]: ").strip()
            folder = Path(answer) if answer else Path.home() / "investbook"
        folder = validate_data_dir(folder, args.new)
        config = {"data_dir": str(folder), "allow_create": args.new, "mcp": {}}
        if args.config:
            config["extra_config"] = str(args.config.expanduser().resolve(strict=True))
        if args.extensions_dir:
            config["extensions"] = str(args.extensions_dir.expanduser().resolve(strict=True))
    config["java"] = java_path()
    jar = build_jar(args)
    if args.update and config.get("mcp", {}).get("package"):
        uv = tool("uv", "Нужен uv для обновления настроенного MCP.")
        subprocess.run([uv, "sync", "--frozen", "--project", config["mcp"]["package"]], check=True)
    check_stopped()
    folder = validate_data_dir(Path(config["data_dir"]), bool(config.get("allow_create")))
    folder.mkdir(parents=True, exist_ok=True)
    backup_database(folder)
    private_dir(DATA)
    shutil.copy2(jar, DATA / "app.jar.next")
    (DATA / "app.jar.next").replace(DATA / "app.jar")
    save_config(config)
    write_private(CONFIG / "application.properties", render_properties(config))
    install_entrypoints(config, not args.no_desktop)
    print("Готово. Запуск: ./run.sh или Investbook в меню приложений. База: " + str(folder))


def setup_mcp(config):
    if not sys.stdin.isatty():
        fail("Настройка MCP интерактивная: ./setup.sh --mcp в терминале ноутбука.")
    uv = tool("uv", "Установка: https://docs.astral.sh/uv/getting-started/installation/")
    tunnel = tool("tunnel-client", "Установите Linux-версию: https://github.com/openai/tunnel-client/releases/latest")
    keyring = tool("secret-tool", "Установите libsecret-tools (Debian/Ubuntu) и разблокируйте связку ключей рабочего стола.")
    tunnel_id = input("ID созданного в OpenAI туннеля (tunnel_...): ").strip()
    if not re.fullmatch(r"tunnel_[A-Za-z0-9_-]+", tunnel_id):
        fail("Неверный ID туннеля.")
    accounts = {}
    print("Укажите счета, доступные ChatGPT. Номера вводятся только здесь и хранятся локально.")
    while True:
        alias = input("Псевдоним счёта [main; пусто после первого — закончить]: ").strip()
        if not alias and accounts:
            break
        alias = alias or "main"
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", alias) or alias in accounts:
            print("Нужен уникальный псевдоним: латинские буквы, цифры, _ или -."); continue
        account = input("ID этого счёта из Investbook: ").strip()
        if not account or account in accounts.values():
            print("Нужен непустой уникальный ID."); continue
        accounts[alias] = account
        if len(accounts) == 20:
            break
    package = ROOT / "integrations/investbook-mcp"
    subprocess.run([uv, "sync", "--frozen", "--project", str(package)], check=True)
    command = shlex.join([str(package / ".venv/bin/python"), "-m", "investbook_mcp.server"])
    profile = "investbook-launcher-" + secrets.token_hex(4)
    subprocess.run([tunnel, "init", "--sample", "sample_mcp_stdio_local", "--profile", profile,
                    "--tunnel-id", tunnel_id, "--mcp-command", command], check=True)
    key = getpass.getpass("Runtime API key туннеля OpenAI (ввод скрыт): ").strip()
    if not key:
        fail("Ключ не введён. Настройка MCP не сохранена.")
    result = subprocess.run([keyring, "store", "--label=Investbook OpenAI tunnel", "application",
                             "investbook-launcher", "profile", profile], input=key, text=True,
                            capture_output=True, timeout=60)
    if result.returncode:
        fail("Не удалось сохранить ключ в связке ключей Linux. MCP не включён.")
    config["mcp"] = {"accounts": accounts, "tunnel": tunnel, "profile": profile, "keyring": keyring, "package": str(package)}
    save_config(config)
    write_private(CONFIG / "application.properties", render_properties(config))
    print("MCP настроен. Выполните ./run.sh, затем выберите этот туннель в ChatGPT. "
          "Доступ из ChatGPT считается проверенным только после успешного запроса.")


def runtime_state(mcp, **kwargs):
    write_private(STATE / "status.json", json.dumps({"mcp": mcp, **kwargs}))


def start_tunnel(config, env):
    mcp = config.get("mcp")
    if not mcp:
        runtime_state("off")
        return None
    runtime_state("starting")
    try:
        secret = subprocess.run([mcp["keyring"], "lookup", "application", "investbook-launcher",
                                 "profile", mcp["profile"]], capture_output=True, text=True, timeout=10)
        if secret.returncode or not secret.stdout.strip():
            raise LaunchError("Связка ключей недоступна или ключ отсутствует.")
        tunnel_env = dict(env, CONTROL_PLANE_API_KEY=secret.stdout.strip())
        with (STATE / "tunnel.log").open("a") as log:
            child = subprocess.Popen([mcp["tunnel"], "run", "--profile", mcp["profile"]],
                                     env=tunnel_env, stdout=log, stderr=subprocess.STDOUT)
        runtime_state("process-running")  # Process running does not prove connectivity.
        return child
    except (OSError, subprocess.TimeoutExpired, LaunchError):
        runtime_state("failed")
        print("Туннель не запущен. Investbook продолжает работать. Проверьте связку ключей и ./run.sh status.", flush=True)
        return None


def serve():
    config = read_config()
    if not config.get("allow_create"):
        validate_data_dir(Path(config["data_dir"]))
    if config.get("extensions") and not Path(config["extensions"]).is_dir():
        fail("Папка расширений отсутствует. Восстановите её перед запуском.")
    private_dir(STATE)
    with (STATE / "supervisor.lock").open("w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            fail("Служба Investbook уже запущена.")
        if port_busy():
            fail("Порт 2030 занят. Завершите другой экземпляр приложения.")
        env = dict(os.environ)
        # Fresh local secret per application start, shared only with the MCP child.
        env["INVESTBOOK_AGENT_TOKEN"] = secrets.token_urlsafe(48)
        env["INVESTBOOK_BASE_URL"] = URL
        locations = []
        if config.get("extra_config"):
            locations.append(Path(config["extra_config"]).as_uri())
        locations.append((CONFIG / "application.properties").as_uri())
        command = [config["java"]]
        if config.get("extensions"):
            command.append("-Dloader.path=" + config["extensions"])
        command += ["-jar", str(DATA / "app.jar"), "--spring.profiles.active=prod",
                    "--spring.config.additional-location=" + ",".join(locations)]
        java = None
        tunnel = None
        stopping = False

        def stop(_signum, _frame):
            nonlocal stopping
            stopping = True
            for process in (tunnel, java):
                if process is not None and process.poll() is None:
                    process.terminate()

        signal.signal(signal.SIGTERM, stop)
        signal.signal(signal.SIGINT, stop)
        runtime_state("starting" if config.get("mcp") else "off")
        try:
            with (STATE / "application.log").open("a") as log:
                java = subprocess.Popen(command, cwd=DATA, env=env, stdout=log, stderr=subprocess.STDOUT)
            if not wait_ready(lambda: not stopping and java.poll() is None):
                fail(f"Investbook не запустился. Журнал: {STATE / 'application.log'}")
            if config.get("allow_create"):
                config["allow_create"] = False
                save_config(config)
            if not stopping:
                tunnel = start_tunnel(config, env)
            while not stopping and java.poll() is None:
                if tunnel is not None and tunnel.poll() is not None:
                    runtime_state("failed")
                    tunnel = None
                time.sleep(0.5)
            if not stopping and java.returncode:
                fail(f"Investbook завершился с ошибкой. Журнал: {STATE / 'application.log'}")
        finally:
            stop(None, None)
            for process in (tunnel, java):
                if process is not None:
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
            runtime_state("stopped")


def start(open_browser=True):
    read_config()
    if not active():
        if port_busy():
            fail("Порт 2030 занят другим экземпляром. Завершите прежний Investbook и повторите ./run.sh.")
        systemctl("start", SERVICE)
    print("Ожидаю готовности Investbook…", flush=True)
    if not wait_ready(active):
        fail(f"Приложение не готово. Выполните ./run.sh status. Журнал: {STATE / 'application.log'}")
    print("Investbook работает.")
    if open_browser and shutil.which("xdg-open"):
        subprocess.Popen(["xdg-open", URL], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         start_new_session=True)
    status()


def status():
    config = read_config()
    running = active()
    print("Приложение: " + ("готово" if running and ready() else "запускается или не готово" if running else "остановлено"))
    if not config.get("mcp"):
        print("ChatGPT: ещё не настроен. Настройка: ./setup.sh --mcp")
    elif running:
        try:
            state = json.loads((STATE / "status.json").read_text())["mcp"]
        except (OSError, ValueError, KeyError):
            state = "starting"
        descriptions = {"starting": "туннель запускается", "process-running": "процесс туннеля запущен; связь проверяется запросом из ChatGPT",
                        "failed": "туннель недоступен; приложение продолжает работать"}
        print("ChatGPT: " + descriptions.get(state, "туннель остановлен"))
    else:
        print("ChatGPT: туннель остановлен вместе с приложением")
    print(f"Журналы: {STATE}")


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description="Одна команда для Investbook на Linux")
    parser.add_argument("action", choices=["setup", "start", "stop", "restart", "status", "serve"])
    parser.add_argument("--data-dir", type=Path, help="папка с investbook3.mv.db")
    parser.add_argument("--new", action="store_true", help="намеренно создать пустой портфель")
    parser.add_argument("--jar", type=Path, help="готовый исполняемый JAR вместо сборки")
    parser.add_argument("--config", type=Path, help="дополнительные настройки прежней установки")
    parser.add_argument("--extensions-dir", type=Path)
    parser.add_argument("--update", action="store_true")
    parser.add_argument("--mcp", action="store_true")
    parser.add_argument("--no-desktop", action="store_true")
    parser.add_argument("--no-browser", action="store_true")
    parser.add_argument("--desktop", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    try:
        if sys.platform != "linux" or sys.version_info < (3, 12):
            fail("Нужен Linux и Python 3.12 или новее.")
        private_dir(STATE)
        if args.action == "serve":
            serve()
        else:
            # Serialize setup/start/stop so a second click cannot launch a duplicate.
            with (STATE / "control.lock").open("w") as lock:
                fcntl.flock(lock, fcntl.LOCK_EX)
                if args.action == "setup":
                    setup(args)
                elif args.action == "status":
                    status()
                elif args.action == "stop":
                    systemctl("stop", SERVICE)
                    print("Investbook и туннель остановлены.")
                else:
                    if args.action == "restart":
                        systemctl("stop", SERVICE)
                    start(not args.no_browser)
        return 0
    except (LaunchError, OSError, subprocess.SubprocessError) as error:
        if isinstance(error, LaunchError):
            message = str(error)
        elif isinstance(error, OSError):
            message = f"Не удалось открыть или запустить {error.filename or 'файл'}: {error.strerror}"
        elif isinstance(error, subprocess.CalledProcessError):
            message = f"Команда {Path(error.cmd[0]).name} завершилась с кодом {error.returncode}. Подробности выше."
        else:
            message = "Время ожидания внешней команды истекло. Проверьте доступность связки ключей и повторите настройку."
        print("Ошибка: " + message, file=sys.stderr)
        if args.desktop and shutil.which("notify-send"):
            subprocess.run(["notify-send", "Investbook: ошибка запуска", message], check=False)
        return 1
    except KeyboardInterrupt:
        print("Отменено.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
