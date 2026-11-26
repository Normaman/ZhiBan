"""Normalized model transport, adapted from the upstream LLMClient contract.

Supports providers exposing the Chat Completions tool-calling protocol. Credentials,
prompts, tool arguments and raw provider responses never enter application logs.
"""

from dataclasses import dataclass
from urllib.parse import urlsplit

import httpx
from pydantic import BaseModel, ConfigDict, Field


class FunctionCall(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    arguments: str = Field(max_length=16000)


class ToolCall(BaseModel):
    model_config = ConfigDict(extra="ignore")
    id: str = Field(min_length=1, max_length=200)
    type: str = "function"
    function: FunctionCall


@dataclass(frozen=True)
class LLMResponse:
    content: str | None
    tool_calls: list[ToolCall]
    usage: dict[str, int] | None = None

    def message(self):
        message = {"role": "assistant", "content": self.content or ""}
        if self.tool_calls:
            message["tool_calls"] = [call.model_dump() for call in self.tool_calls]
        return message


class LLMClient:
    def __init__(self, settings):
        self.settings = settings

    async def chat(self, messages, tools=None, *, use_tools=True, parallel_tool_calls=None):
        body = {
            "model": self.settings.llm_model,
            "messages": messages,
            "stream": False,
            "max_tokens": 1500,
        }
        # DeepSeek enables thinking by default and requires reasoning_content
        # replay with tools. This draft workflow explicitly uses non-thinking
        # mode rather than persisting or replaying private reasoning.
        if urlsplit(self.settings.llm_url).hostname == "api.deepseek.com":
            body["thinking"] = {"type": "disabled"}
        if tools and use_tools:
            body.update(tools=tools, tool_choice="auto")
            if parallel_tool_calls is not None:
                body["parallel_tool_calls"] = parallel_tool_calls
        async with httpx.AsyncClient(
            timeout=httpx.Timeout(20, connect=5), follow_redirects=False, trust_env=False
        ) as client:
            response = await client.post(
                self.settings.llm_url.rstrip("/") + "/chat/completions",
                headers={"Authorization": "Bearer " + self.settings.llm_key},
                json=body,
            )
            response.raise_for_status()
            if len(response.content) > 256000:
                raise ValueError("MODEL_RESPONSE_TOO_LARGE")
            payload = response.json()
            choice = payload["choices"][0]
            if choice.get("finish_reason") in {"length", "content_filter"}:
                raise ValueError("MODEL_RESPONSE_INCOMPLETE")
            message = choice["message"]
            calls = [ToolCall.model_validate(call) for call in message.get("tool_calls", [])]
            if len(calls) > 8 or len({call.id for call in calls}) != len(calls):
                raise ValueError("INVALID_TOOL_BATCH")
            if any(call.type != "function" for call in calls):
                raise ValueError("UNSUPPORTED_TOOL_TYPE")
            content = message.get("content")
            if content is not None and not isinstance(content, str):
                raise ValueError("INVALID_MODEL_CONTENT")
            raw_usage = payload.get("usage")
            usage = (
                {
                    key: value
                    for key, value in raw_usage.items()
                    if key
                    in {
                        "prompt_tokens",
                        "completion_tokens",
                        "total_tokens",
                        "prompt_cache_hit_tokens",
                        "prompt_cache_miss_tokens",
                    }
                    and type(value) is int
                    and value >= 0
                }
                if isinstance(raw_usage, dict)
                else {}
            )
            return LLMResponse(content=content, tool_calls=calls, usage=usage or None)
