import httpx
import pytest
from mcp import Client
from investbook_mcp.client import AgentClient, Settings
from investbook_mcp.server import build_server

TOKEN = "synthetic-token-with-at-least-thirty-two-characters"
NAMES = {"list_accounts", "get_portfolio", "get_allocation", "get_performance", "get_income", "get_transactions"}


async def test_discovery_and_structured_tool_result():
    requests = []
    def handler(request):
        requests.append(request)
        return httpx.Response(200, json={"schema_version": "1", "meta": {}, "data": [{"id": "main"}], "warnings": []})
    server = build_server(AgentClient(Settings(token=TOKEN), transport=httpx.MockTransport(handler)))
    async with Client(server) as client:
        tools = (await client.list_tools()).tools
        assert {tool.name for tool in tools} == NAMES
        assert all(tool.annotations.read_only_hint is True and tool.annotations.destructive_hint is False for tool in tools)
        assert all(tool.output_schema for tool in tools)
        result = await client.call_tool("list_accounts", {})
        assert not result.is_error
        assert result.structured_content["data"] == [{"id": "main"}]
    assert len(requests) == 1


@pytest.mark.parametrize("name,args", [
    ("get_portfolio", {"account_ids": []}),
    ("get_portfolio", {"account_ids": ["main", "main"]}),
    ("get_portfolio", {"account_ids": ["../private"]}),
    ("get_income", {"account_ids": ["main"], "date_from": "2025-02-30", "date_to": "2025-03-01"}),
    ("get_income", {"account_ids": ["main"], "date_from": "2025-03-02", "date_to": "2025-03-01"}),
    ("get_transactions", {"account_ids": ["main"], "date_from": "2025-01-01", "date_to": "2025-03-01", "limit": 201}),
    ("get_transactions", {"account_ids": ["main"], "date_from": "2025-01-01", "date_to": "2025-03-01", "limit": True}),
])
async def test_bad_arguments_are_mcp_errors_before_http(name, args):
    requests = []
    def handler(request):
        requests.append(request)
        return httpx.Response(500)
    server = build_server(AgentClient(Settings(token=TOKEN), transport=httpx.MockTransport(handler)))
    async with Client(server) as client:
        result = await client.call_tool(name, args)
        assert result.is_error
    assert requests == []


async def test_upstream_failure_is_mcp_error_with_stable_code():
    server = build_server(AgentClient(Settings(token=TOKEN), transport=httpx.MockTransport(
        lambda _: httpx.Response(401, json={"error": {"code": "UNAUTHORIZED", "message": TOKEN}}))))
    async with Client(server) as client:
        result = await client.call_tool("get_portfolio", {"account_ids": ["main"]})
        assert result.is_error
        assert "UNAUTHORIZED" in str(result.content)
        assert TOKEN not in str(result)
