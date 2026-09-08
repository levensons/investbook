#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
JDK_DIR="$PROJECT_DIR/.local/jdk-26.0.2"

if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
    cat <<'EOF'
Запуск Investbook: ./run-local.sh
Подготовка Java без запуска: ./run-local.sh --setup
Дополнительные аргументы передаются Maven, например:
  ./run-local.sh -Dspring-boot.run.arguments=--server.port=2031

Требуется JDK 26. Если он не найден, скрипт загрузит OpenJDK 26.0.2
в .local/ внутри проекта (автоматическая установка: Linux x86_64).
Приложение использует профиль prod и базу ~/investbook/investbook3.mv.db.
Адрес по умолчанию: http://localhost:2030. Остановка: Ctrl+C.
EOF
    exit 0
fi

is_jdk_26() {
    [[ -x "$1/bin/java" && -x "$1/bin/javac" ]] &&
        "$1/bin/javac" -version 2>&1 | grep -Eq '^javac 26([. +_-]|$)'
}

if is_jdk_26 "$JDK_DIR"; then
    export JAVA_HOME="$JDK_DIR"
elif [[ -n "${JAVA_HOME:-}" ]] && is_jdk_26 "$JAVA_HOME"; then
    export JAVA_HOME
elif command -v javac >/dev/null 2>&1 &&
    SYSTEM_JDK="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")" &&
    is_jdk_26 "$SYSTEM_JDK"; then
    export JAVA_HOME="$SYSTEM_JDK"
else
    if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
        echo 'Установите JDK 26 и задайте JAVA_HOME; автозагрузка поддерживает Linux x86_64.' >&2
        exit 1
    fi
    for tool in curl tar sha256sum; do
        command -v "$tool" >/dev/null 2>&1 || {
            echo "Для установки Java нужна команда: $tool" >&2
            exit 1
        }
    done
    if [[ -e "$JDK_DIR" ]]; then
        echo "В $JDK_DIR найден неполный или неподходящий JDK. Переместите его и повторите запуск." >&2
        exit 1
    fi
    mkdir -p "$PROJECT_DIR/.local"
    DOWNLOAD_DIR="$(mktemp -d "$PROJECT_DIR/.local/jdk-download.XXXXXX")"
    trap 'rm -rf -- "$DOWNLOAD_DIR"' EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    # Official OpenJDK build and SHA-256 from download.java.net.
    JDK_URL='https://download.java.net/java/GA/jdk26.0.2/818d462d89b645c7a1aad49066c454e5/10/GPL/openjdk-26.0.2_linux-x64_bin.tar.gz'
    JDK_SHA256='2da09e9db53e5c4f9eeec045f49e7d8fbcd8e4153edbf0c269f520ff82fd4198'
    echo 'Загрузка OpenJDK 26.0.2 (около 217 МБ, только при первом запуске)…'
    curl --fail --location --show-error --retry 3 --connect-timeout 20 \
        --output "$DOWNLOAD_DIR/jdk.tar.gz" "$JDK_URL"
    printf '%s  %s\n' "$JDK_SHA256" "$DOWNLOAD_DIR/jdk.tar.gz" | sha256sum --check --status
    tar -xzf "$DOWNLOAD_DIR/jdk.tar.gz" -C "$DOWNLOAD_DIR"
    is_jdk_26 "$DOWNLOAD_DIR/jdk-26.0.2"
    mv -- "$DOWNLOAD_DIR/jdk-26.0.2" "$JDK_DIR"
    rm -rf -- "$DOWNLOAD_DIR"
    trap - EXIT INT TERM
    export JAVA_HOME="$JDK_DIR"
fi

export PATH="$JAVA_HOME/bin:$PATH"
echo "Java: $JAVA_HOME"
if [[ "${1:-}" == --setup ]]; then
    "$JAVA_HOME/bin/java" -version
    exit 0
fi

cd -- "$PROJECT_DIR"
echo 'Запуск Investbook. Адрес по умолчанию: http://localhost:2030 (остановка: Ctrl+C)'
exec bash ./mvnw -DskipTests -Dspring-boot.run.profiles=prod spring-boot:run "$@"
