"""Fixed-route client: only read-only agent endpoints on a numeric loopback address."""
from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import json
import os
import re
from urllib.parse import urlsplit

from mcp.server.mcpserver.exceptions import ToolError
import httpx
from pydantic import BaseModel, ConfigDict, Field
from typing import Any, Literal

MAX_RESPONSE_BYTES = 2 * 1024 * 1024
ROUTES = frozenset({"accounts", "portfolio", "allocation", "performance", "income", "transactions"})
ERROR_CODES = frozenset({"INVALID_ARGUMENT", "FORBIDDEN", "UNAUTHORIZED", "DISABLED", "METHOD_NOT_ALLOWED",
                         "INSUFFICIENT_DATA", "RESULT_TOO_LARGE", "UPSTREAM_UNAVAILABLE", "TIMEOUT", "UNSUPPORTED_METRIC"})


class AgentError(ToolError):
    """Safe error: never include upstream bodies, URLs, token or account configuration."""


class Envelope(BaseModel):
    model_config = ConfigDict(extra="forbid")
    schema_version: Literal["1"]
    meta: dict[str, Any]
    data: dict[str, Any] | list[dict[str, Any]]
    warnings: list[str] = Field(default_factory=list)


@dataclass(frozen=True)
class Settings:
    base_url: str = "http://127.0.0.1:2030"
    token: str = field(default="", repr=False)

    def __post_init__(self) -> None:
        try:
            url = urlsplit(self.base_url)
            valid = (url.scheme == "http" and url.hostname in {"127.0.0.1", "::1"}
                     and url.port is not None and 1 <= url.port <= 65535 and url.path in {"", "/"}
                     and not url.query and not url.fragment and not url.username and not url.password)
        except ValueError:
            valid = False
        if not valid or not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", self.token):
            raise AgentError("CONFIGURATION_ERROR: numeric loopback URL and a 32..256 character token are required")

    @classmethod
    def from_env(cls) -> Settings:
        return cls(os.environ.get("INVESTBOOK_BASE_URL", "http://127.0.0.1:2030"),
                   os.environ.get("INVESTBOOK_AGENT_TOKEN", ""))


class AgentClient:
    def __init__(self, settings: Settings, *, transport: httpx.AsyncBaseTransport | None = None):
        self.settings = settings
        self.transport = transport

    async def get(self, route: str, params: list[tuple[str, str]] | None = None) -> Envelope:
        if route not in ROUTES:
            raise AgentError("INVALID_ARGUMENT")
        try:
            async with asyncio.timeout(25):
                return await self._get(route, params)
        except (TimeoutError, httpx.TimeoutException):
            raise AgentError("TIMEOUT") from None
        except httpx.HTTPError:
            raise AgentError("UPSTREAM_UNAVAILABLE") from None

    async def _get(self, route: str, params: list[tuple[str, str]] | None) -> Envelope:
        # Ignore proxy environment variables, including credentials; never forward bearer tokens on redirects.
        async with httpx.AsyncClient(trust_env=False, follow_redirects=False, timeout=10,
                                    transport=self.transport) as client:
            for attempt in range(2):
                try:
                    async with client.stream("GET", self.settings.base_url.rstrip("/") + "/api/agent/v1/" + route,
                                             params=params, headers={"Authorization": "Bearer " + self.settings.token,
                                                                     "Accept": "application/json"}) as response:
                        if response.status_code in {502, 503, 504} and attempt == 0:
                            await asyncio.sleep(0.1)
                            continue
                        if 300 <= response.status_code < 400:
                            raise AgentError("UPSTREAM_UNAVAILABLE")
                        body = bytearray()
                        async for chunk in response.aiter_bytes():
                            if len(body) + len(chunk) > MAX_RESPONSE_BYTES:
                                raise AgentError("RESULT_TOO_LARGE")
                            body.extend(chunk)
                        try:
                            payload = json.loads(body)
                        except (ValueError, UnicodeError, RecursionError):
                            raise AgentError("UPSTREAM_UNAVAILABLE") from None
                        if response.status_code != 200:
                            error = payload.get("error") if isinstance(payload, dict) else None
                            code = error.get("code") if isinstance(error, dict) else None
                            raise AgentError(code if isinstance(code, str) and code in ERROR_CODES else "UPSTREAM_UNAVAILABLE")
                        try:
                            return Envelope.model_validate(payload)
                        except ValueError:
                            raise AgentError("UPSTREAM_UNAVAILABLE") from None
                except httpx.ConnectError:
                    if attempt:
                        raise
                    await asyncio.sleep(0.1)
        raise AgentError("UPSTREAM_UNAVAILABLE")
