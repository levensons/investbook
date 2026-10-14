# Investbook MCP: локальный запуск

MCP позволяет клиенту читать шесть аналитических инструментов Investbook. Java рассчитывает показатели и ограничивает доступ к счетам; отдельный Python-процесс передаёт ответы через `stdio`. Модельный API-ключ для этой работы не нужен.

Начните с [контракта и методик](contract.md), [ограничений](limitations.md) и [статуса реализации](implementation-status.md). MCP не даёт инструментов торговли, SQL, изменения данных, чтения файлов или запросов к произвольным URL.

## Зависимости и установка

Нужны Investbook из этой версии исходников, JDK 26, Python 3.12+ и [uv](https://docs.astral.sh/uv/getting-started/installation/). Maven закреплён wrapper-ом проекта. Примеры ниже — для Bash из корня репозитория; в Windows для Maven используется `mvnw.cmd`, Python находится в `.venv\Scripts\python.exe`.

```bash
uv sync --frozen --project integrations/investbook-mcp
```

`uv.lock` фиксирует версии и хеши транзитивных зависимостей. Используется официальный `mcp==2.3.0`: в актуальной версии SDK класс сервера называется `MCPServer`. Отдельный пакет `fastmcp` не нужен. Обновление lockfile должно сопровождаться протокольными тестами.

## Конфигурация доступа

1. Скопируйте [пример](application-agent.properties.example) в приватный каталог **вне репозитория** под именем `application-agent.properties`.
2. В `investbook.agent.accounts.main` укажите локальный ID нужного счёта из Investbook. При необходимости добавьте другой псевдоним. В ответах MCP будут только псевдонимы `main`, `second`. Реальные номера счетов не отправляйте в чат и не коммитьте.
3. Создайте случайный токен длиной не менее 32 символов `A-Z a-z 0-9 _ -` и передайте одинаковое значение двум процессам через переменную `INVESTBOOK_AGENT_TOKEN`. Не используйте пример тестового токена из исходников. API откажется запускаться при включении без токена или списка счетов.

В Bash можно создать токен без вывода значения:

```bash
export INVESTBOOK_AGENT_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')"
```

Это временный токен текущей оболочки. Запустите оба процесса из неё или используйте локальный менеджер секретов. Для повторного запуска сохраняйте секрет через защищённый механизм вашей ОС; не добавляйте его в командные аргументы, URL, историю терминала или файлы проекта. Ротация — остановить оба процесса, заменить токен и запустить их снова.

По умолчанию API выключен. Отключение: `investbook.agent.enabled=false` и перезапуск Java. Непосредственный запрос должен приходить с loopback; проксируемые заголовки отклоняются. Не проксируйте весь Investbook наружу: существующий веб-интерфейс имеет другие правила доступа.

## Запуск

Для разработки с отдельной тестовой БД из профиля `dev`:

```bash
# Укажите абсолютный путь к подготовленному файлу; без секретов в аргументах.
export SPRING_CONFIG_ADDITIONAL_LOCATION=file:/absolute/private/application-agent.properties
bash mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Для существующей установки сохраните её текущий профиль, каталог данных и параметры БД; добавьте этот конфигурационный файл и переменную токена к обычному способу запуска обновлённого приложения. `dev` не следует выбирать ради подключения к существующей личной БД: он использует собственный относительный путь. Новая схема БД для MCP не нужна.

MCP-процесс запускается клиентом по команде:

```bash
/absolute/path/to/investbook/integrations/investbook-mcp/.venv/bin/python -m investbook_mcp.server
```

Он читает `INVESTBOOK_AGENT_TOKEN` из окружения. `INVESTBOOK_BASE_URL` по умолчанию `http://127.0.0.1:2030`; разрешён только числовой loopback-адрес с явным портом, без пути/параметров/логина. Если запустить процесс вручную, он будет ждать MCP-сообщения в stdin: отсутствие обычного приглашения — нормально. stdout занят протоколом, диагностика идёт в stderr.

У MCP-клиента настройте `command` как абсолютный путь к Python выше и `args` как `["-m", "investbook_mcp.server"]`. Передайте секрет через защищённое окружение запуска клиента. Не помещайте его в публикуемый JSON конфигурации. `stdio`-процесс не нуждается в постоянно открытом сетевом порте.

## Проверки

```bash
uv run --frozen --project integrations/investbook-mcp pytest integrations/investbook-mcp/tests -q
export INVESTBOOK_MCP_PYTHON="$PWD/integrations/investbook-mcp/.venv/bin/python"
bash mvnw test
```

`INVESTBOOK_MCP_PYTHON` включает `AgentMcpIntegrationTest`: JUnit поднимает отдельный сервер на случайном loopback-порту, создаёт синтетическую H2-базу, запускает MCP-процесс и настоящий клиент, проверяет все шесть инструментов и отказ для чужого счёта. Реальные данные не нужны. При отсутствии этой переменной интеграционный тест явно пропускается; остальные Java-тесты работают без Python.

Быстрая проверка только MCP-части Java:

```bash
bash mvnw -Dtest=AgentApiTest,AgentDisabledTest,AgentMcpIntegrationTest test
```

В подготовленной облачной среде сначала выполните `source /workspace/.investbook-setup/activate.sh`. Он выбирает нужную Java и сетевые параметры для существующих MOEX-тестов. Результаты полного запуска — в `target/surefire-reports`, проверка MCP-процесса — в `target/mcp-e2e.log` (только синтетические данные).

При `UNAUTHORIZED` проверьте одинаковый токен у Java и Python; при `FORBIDDEN` — разрешённый псевдоним и прямое локальное соединение; при `DISABLED` — конфигурацию Java. `INSUFFICIENT_DATA`, `MISSING_FX` и `MISSING_QUOTE` требуют нужных отчётов/истории котировок, а не повторов запроса. `RESULT_TOO_LARGE` — сузить счета или период. `TIMEOUT`/`UPSTREAM_UNAVAILABLE` — проверить состояние приложения и БД, не включать логирование HTTP-заголовков с секретом.

## Частное подключение к ChatGPT

Проверена [официальная документация Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels) на 2026-10-14. Она поддерживает локальный `stdio`-сервер. Доступ к конкретному аккаунту пользователя ещё не проверен, туннель не создавался, реальный вызов из ChatGPT не выполнялся.

Дальнейшие действия на компьютере, где находится ваш портфель:

1. Убедиться, что Java и локальный MCP работают. Сохранить привязку приложения к `127.0.0.1`.
2. В Platform выбрать нужную организацию и настроить туннель. Для создания нужны `Tunnels Read + Manage`, для запуска — `Tunnels Read + Use`. Туннель должен быть связан с целевым workspace ChatGPT; право добавлять custom MCP проверяется отдельно.
3. Установить поддерживаемый `tunnel-client` из [официальных релизов](https://github.com/openai/tunnel-client/releases/latest), проверить текущую справку `tunnel-client help quickstart`. Runtime-ключ туннеля (`CONTROL_PLANE_API_KEY`) отличается от локального `INVESTBOOK_AGENT_TOKEN`; вводить его только локально через безопасный механизм.
4. Для `--mcp-command` использовать абсолютный путь к Python и модуль `investbook_mcp.server`. Клиент должен наследовать локальный токен, чтобы запущенный им MCP мог обратиться к Java.

Ориентир из актуальной документации; идентификатор заменить своим:

```bash
tunnel-client init --sample sample_mcp_stdio_local --profile investbook \
  --tunnel-id YOUR_TUNNEL_ID \
  --mcp-command '/absolute/path/to/.venv/bin/python -m investbook_mcp.server'
tunnel-client doctor --profile investbook --explain
tunnel-client run --profile investbook
```

5. В ChatGPT добавить custom MCP server с Connection: Tunnel, выбрать тот же туннель и проверить список счетов, состав, доход и сделки. Названия пунктов UI следует сверить с текущей версией. Успешный локальный тест не заменяет эту проверку.

Java, компьютер и `tunnel-client` должны работать во время использования. Доступность канала и разрешения аккаунта — отдельный этап; в этой разработке они не заменялись публичным сервером, VPS или загрузкой базы в облако.
