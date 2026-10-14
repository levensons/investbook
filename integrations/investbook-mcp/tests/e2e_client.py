"""Launched by AgentMcpIntegrationTest against a real, isolated Java/H2 server."""
import asyncio
import os
import sys
from mcp import Client, StdioServerParameters


async def main():
    parameters = StdioServerParameters(command=sys.executable, args=["-m", "investbook_mcp.server"],
                                       env={"INVESTBOOK_BASE_URL": os.environ["INVESTBOOK_BASE_URL"],
                                            "INVESTBOOK_AGENT_TOKEN": os.environ["INVESTBOOK_AGENT_TOKEN"]})
    async with Client(parameters, read_timeout_seconds=30) as client:
        tools = (await client.list_tools()).tools
        assert len(tools) == 6
        assert all(t.annotations.read_only_hint for t in tools)
        async def call(name, args):
            result = await client.call_tool(name, args)
            assert not result.is_error, (name, result.content)
            return result.structured_content["data"]
        accounts = await call("list_accounts", {})
        assert {a["id"] for a in accounts} == {"main", "second", "empty", "fx"}
        portfolio = await call("get_portfolio", {"account_ids": ["main"]})
        assert portfolio["total_rub"] == "1192"
        allocation = await call("get_allocation", {"account_ids": ["main"]})
        assert allocation["denominator_rub"] == "1192"
        period = {"account_ids": ["main"], "date_from": "2025-01-02", "date_to": "2025-01-02"}
        performance = await call("get_performance", period)
        assert performance["value_change_rub"] == "92" and performance["twr"] is None
        income = await call("get_income", period)
        assert income["groups"][0]["totals"]["dividend_rub"] == "20"
        transactions = await call("get_transactions", period)
        assert transactions["transactions"][0]["quantity"] == "-2"
        forbidden = await client.call_tool("get_portfolio", {"account_ids": ["hidden"]})
        assert forbidden.is_error and "FORBIDDEN" in str(forbidden.content)
        print("MCP stdio -> HTTP -> Java -> H2: all six tools passed")


if __name__ == "__main__":
    asyncio.run(main())
