"""Immutable settings per run; admin-managed configuration overrides deployment defaults."""

from dataclasses import replace

from pydantic import BaseModel, ConfigDict, Field

from .agent.runner import ModelAgent


class ModelConfiguration(BaseModel):
    model_config = ConfigDict(extra="forbid")
    base_url: str = Field(min_length=1, max_length=1000)
    model: str = Field(min_length=1, max_length=200)
    api_key: str = Field(min_length=8, max_length=4096, repr=False)
    max_rounds: int = Field(ge=1, le=8)
    timeout_seconds: int = Field(ge=5, le=70)


def snapshot(settings, configuration):
    if not configuration.get("managed"):
        return settings
    if not configuration.get("enabled"):
        return replace(settings, llm_url="", llm_model="", llm_key="")
    payload = {key: configuration[key] for key in ModelConfiguration.model_fields}
    return candidate(settings, ModelConfiguration.model_validate(payload))


def candidate(settings, configuration):
    value = replace(
        settings,
        llm_url=configuration.base_url,
        llm_model=configuration.model,
        llm_key=configuration.api_key,
        llm_max_rounds=configuration.max_rounds,
        agent_timeout=configuration.timeout_seconds,
    )
    value.validate()
    return value


class ProbeConnector:
    async def products(self):
        return []


async def test_configuration(settings, configuration):
    # No live connector, history, shop knowledge or buyer data enters a model test.
    context = {
        "id": "configuration-test",
        "message_id": "synthetic-message",
        "conversation_revision": 1,
        "knowledge_version": "configuration-test-v1",
        "history": [],
        "message": {"message_type": "text", "content": "测试咨询：现货付款后什么时候发货？"},
        "knowledge": [
            {
                "source_type": "shop_policy",
                "source_id": "test-shipping",
                "topics": ["shipping"],
                "text": "测试规则：现货付款后48小时内发出。",
            }
        ],
    }
    try:
        result = await ModelAgent(candidate(settings, configuration), ProbeConnector()).generate(
            context
        )
        passed = result["outcome"] == "draft" and any(
            t["status"] == "ok" for t in result["tool_trace"]
        )
        return {
            "passed": passed,
            "error_code": None
            if passed
            else next(iter(result.get("risk_codes", [])), "MODEL_TOOL_TEST_FAILED"),
        }
    except Exception:
        return {"passed": False, "error_code": "MODEL_REQUEST_FAILED"}
