import json
from dataclasses import replace
from unittest.mock import AsyncMock

import httpx
import pytest
from copilot.contracts import RunRequest
from copilot.model_configuration import ModelConfiguration, snapshot
from copilot.runtime import Runtime
from copilot.settings import Settings
from test_fakes import FakeExecutionAuthority


def settings(tmp_path):
    return Settings(token="test-internal-token-long-enough", shop_id="fixture-shop")


def configuration(model="model-one", id="configuration-one"):
    return {
        "managed": True,
        "enabled": True,
        "configuration_id": id,
        "base_url": "https://model.example/v1",
        "model": model,
        "api_key": "dummy-test-key",
        "max_rounds": 4,
        "timeout_seconds": 60,
    }


def request(id):
    return RunRequest(
        run_id=id,
        shop_id="fixture-shop",
        conversation_id="conversation",
        message_id="message",
        knowledge_version="kb-v1",
    )


def test_disabled_admin_configuration_does_not_fall_back_to_environment_key(tmp_path):
    original = replace(
        settings(tmp_path),
        llm_url="https://model.example/v1",
        llm_model="environment-model",
        llm_key="environment-key",
    )
    disabled = snapshot(original, {"managed": True, "enabled": False})
    assert not disabled.model_configured
    assert original.llm_key == "environment-key"
    assert snapshot(original, {"managed": False}) is original


def test_model_configuration_repr_does_not_include_secret():
    payload = {
        key: value
        for key, value in configuration().items()
        if key in ModelConfiguration.model_fields
    }
    assert "dummy-test-key" not in repr(ModelConfiguration.model_validate(payload))


async def test_new_run_reads_active_configuration_without_restart_and_old_run_keeps_cached_result(
    tmp_path, monkeypatch
):
    runtime = Runtime(settings(tmp_path))
    authority = FakeExecutionAuthority()
    runtime.core.post = AsyncMock(side_effect=authority.post)
    current = configuration()
    observed = []

    class Graph:
        def __init__(self, value, connector):
            self.settings = value

        async def generate(self, context):
            observed.append(self.settings.llm_model)
            return {"outcome": "draft", "engine": "llm_agent", "content": self.settings.llm_model}

    async def fetch(path):
        if path.endswith("/model-config"):
            payload = dict(current)
        else:
            payload = {
                "shop_id": "fixture-shop",
                "conversation_id": "conversation",
                "message_id": "message",
                "knowledge_version": "kb-v1",
            }
        return httpx.Response(200, json=payload, request=httpx.Request("GET", "http://core/"))

    runtime.core.get = AsyncMock(side_effect=fetch)
    monkeypatch.setattr("copilot.runtime.DraftGraph", Graph)
    try:
        first = await runtime.generate(request("run-one"))
        current.update(configuration("model-two", "configuration-two"))
        second = await runtime.generate(request("run-two"))
        replay = await runtime.generate(request("run-one"))
        assert observed == ["model-one", "model-two"]
        assert first == replay
        assert second["model_config_version"] == "configuration-two"
        assert first["model_config_version"] == "configuration-one"
        assert not runtime.settings.model_configured
        assert "dummy-test-key" not in json.dumps(first)
    finally:
        await runtime.core.aclose()


async def test_configuration_failure_does_not_silently_use_environment_model(tmp_path):
    runtime = Runtime(settings(tmp_path))
    authority = FakeExecutionAuthority()
    runtime.core.post = AsyncMock(side_effect=authority.post)
    runtime.graph.generate = AsyncMock()

    async def fetch(path):
        payload = {
            "shop_id": "fixture-shop",
            "conversation_id": "conversation",
            "message_id": "message",
            "knowledge_version": "kb-v1",
        }
        return httpx.Response(
            503 if path.endswith("/model-config") else 200,
            json=payload,
            request=httpx.Request("GET", "http://core/"),
        )

    runtime.core.get = AsyncMock(side_effect=fetch)
    try:
        with pytest.raises(httpx.HTTPStatusError):
            await runtime.generate(request("run-unavailable"))
        runtime.graph.generate.assert_not_awaited()
    finally:
        await runtime.core.aclose()
