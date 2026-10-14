import httpx
import pytest
from investbook_mcp.client import AgentClient, AgentError, Settings, MAX_RESPONSE_BYTES

TOKEN = "synthetic-token-with-at-least-thirty-two-characters"
PAYLOAD = {"schema_version": "1", "meta": {}, "data": {"amount": "123.45"}, "warnings": []}


@pytest.mark.parametrize("url", ["https://example.com", "http://localhost:2030", "http://127.0.0.1:2030/api",
                                      "http://127.0.0.1:2030?secret=x", "http://user:pass@127.0.0.1:2030",
                                      "http://127.0.0.1:99999", "http://127.0.0.1.evil:2030"])
def test_only_fixed_numeric_loopback_urls_are_accepted(url):
    with pytest.raises(AgentError, match="CONFIGURATION_ERROR"):
        Settings(url, TOKEN)


def test_secret_not_in_representation_and_required():
    assert TOKEN not in repr(Settings(token=TOKEN))
    with pytest.raises(AgentError):
        Settings(token="")


async def test_fixed_route_secret_header_and_exact_decimal_strings():
    def handler(request):
        assert request.url.path == "/api/agent/v1/portfolio"
        assert request.headers["Authorization"] == "Bearer " + TOKEN
        assert TOKEN not in str(request.url)
        assert request.url.params.get_list("account_ids") == ["one", "two"]
        return httpx.Response(200, json=PAYLOAD)
    client = AgentClient(Settings(token=TOKEN), transport=httpx.MockTransport(handler))
    result = await client.get("portfolio", [("account_ids", "one"), ("account_ids", "two")])
    assert result.data["amount"] == "123.45"
    with pytest.raises(AgentError, match="INVALID_ARGUMENT"):
        await client.get("../transactions")


async def test_redirect_never_forwards_token():
    requests = []
    def handler(request):
        requests.append(request)
        return httpx.Response(302, headers={"Location": "https://example.com/steal"})
    with pytest.raises(AgentError, match="UPSTREAM_UNAVAILABLE"):
        await AgentClient(Settings(token=TOKEN), transport=httpx.MockTransport(handler)).get("accounts")
    assert len(requests) == 1


async def test_transient_status_retried_once():
    attempts = 0
    def handler(request):
        nonlocal attempts
        attempts += 1
        return httpx.Response(503 if attempts == 1 else 200, json=PAYLOAD)
    result = await AgentClient(Settings(token=TOKEN), transport=httpx.MockTransport(handler)).get("accounts")
    assert attempts == 2 and result.schema_version == "1"


async def test_permanent_error_sanitized_without_retry():
    attempts = 0
    def handler(request):
        nonlocal attempts
        attempts += 1
        return httpx.Response(403, json={"error": {"code": "FORBIDDEN", "message": TOKEN}})
    with pytest.raises(AgentError) as exc:
        await AgentClient(Settings(token=TOKEN), transport=httpx.MockTransport(handler)).get("accounts")
    assert str(exc.value) == "FORBIDDEN" and attempts == 1


async def test_timeout_and_connection_errors_do_not_expose_request():
    for error, code, count in [(httpx.ReadTimeout, "TIMEOUT", 1), (httpx.ConnectError, "UPSTREAM_UNAVAILABLE", 2)]:
        attempts = 0
        def handler(request):
            nonlocal attempts
            attempts += 1
            raise error(TOKEN, request=request)
        with pytest.raises(AgentError) as exc:
            await AgentClient(Settings(token=TOKEN), transport=httpx.MockTransport(handler)).get("accounts")
        assert str(exc.value) == code and attempts == count


@pytest.mark.parametrize("response", [httpx.Response(200, content=b"x" * (MAX_RESPONSE_BYTES + 1)),
                                      httpx.Response(200, content=b"not json"),
                                      httpx.Response(200, json={"schema_version": "2"})])
async def test_bounded_and_validated_responses(response):
    with pytest.raises(AgentError):
        await AgentClient(Settings(token=TOKEN), transport=httpx.MockTransport(lambda _: response)).get("accounts")
