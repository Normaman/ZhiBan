"""Independent Agent instances rely on the Core execution protocol, not local state."""

import asyncio
from unittest.mock import AsyncMock

import httpx
import pytest
from copilot.contracts import RunRequest
from copilot.runtime import Runtime
from copilot.settings import Settings
from test_fakes import FakeExecutionAuthority


def request(**changes):
    return RunRequest(
        **{
            "run_id": "run-1",
            "shop_id": "shop-1",
            "conversation_id": "conversation-1",
            "message_id": "message-1",
            "knowledge_version": "kb-1",
            **changes,
        }
    )


def runtime(authority):
    value = Runtime(Settings(shop_id="shop-1", token="isolated-test-internal-token-32"))

    async def get(path):
        payload = (
            {"managed": False}
            if path.endswith("model-config")
            else {
                "id": "run-1",
                **request().model_dump(),
            }
        )
        return httpx.Response(200, json=payload, request=httpx.Request("GET", "http://core" + path))

    value.core.get = AsyncMock(side_effect=get)
    value.core.post = AsyncMock(side_effect=authority.post)
    value.graph.generate = AsyncMock(return_value={"outcome": "draft", "content": "fixture"})
    return value


async def test_replicas_and_restarted_instance_reuse_core_result(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    authority = FakeExecutionAuthority()
    first, second = runtime(authority), runtime(authority)
    try:
        results = await asyncio.gather(first.generate(request()), second.generate(request()))
        assert results[0] == results[1]
        assert first.graph.generate.await_count + second.graph.generate.await_count == 1
    finally:
        await first.core.aclose()
        await second.core.aclose()
    restarted = runtime(authority)
    try:
        assert await restarted.generate(request()) == results[0]
        restarted.graph.generate.assert_not_awaited()
        with pytest.raises(httpx.HTTPStatusError):
            await restarted.generate(request(message_id="different-message"))
        assert not list(tmp_path.iterdir())
    finally:
        await restarted.core.aclose()


async def test_failure_releases_execution_for_another_instance():
    authority = FakeExecutionAuthority()
    first, second = runtime(authority), runtime(authority)
    first.graph.generate.side_effect = RuntimeError("fixture-generation-failure")
    try:
        with pytest.raises(RuntimeError, match="fixture-generation-failure"):
            await first.generate(request())
        assert authority.executions["run-1"]["lease_id"] is None
        assert (await second.generate(request()))["outcome"] == "draft"
    finally:
        await first.core.aclose()
        await second.core.aclose()


async def test_lost_completion_response_preserves_committed_result():
    authority = FakeExecutionAuthority()
    first, second = runtime(authority), runtime(authority)

    async def lose_response(path, **kwargs):
        response = await authority.post(path, **kwargs)
        if path.endswith("/complete"):
            raise httpx.ReadError("fixture-lost-response")
        return response

    first.core.post = AsyncMock(side_effect=lose_response)
    try:
        with pytest.raises(httpx.ReadError):
            await first.generate(request())
        assert (await second.generate(request()))["outcome"] == "draft"
        second.graph.generate.assert_not_awaited()
    finally:
        await first.core.aclose()
        await second.core.aclose()


async def test_cancellation_releases_execution_without_local_cache():
    authority = FakeExecutionAuthority()
    first = runtime(authority)
    entered = asyncio.Event()

    async def blocked(context):
        entered.set()
        await asyncio.Event().wait()

    first.graph.generate.side_effect = blocked
    task = asyncio.create_task(first.generate(request()))
    try:
        await asyncio.wait_for(entered.wait(), timeout=2)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert authority.executions["run-1"]["lease_id"] is None
    finally:
        task.cancel()
        await first.core.aclose()
