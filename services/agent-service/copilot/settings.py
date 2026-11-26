import os
from dataclasses import dataclass, field
from urllib.parse import urlsplit


@dataclass
class Settings:
    core_url: str = field(default_factory=lambda: os.getenv("CORE_URL", "http://localhost:8080"))
    token: str = field(default_factory=lambda: os.getenv("INTERNAL_TOKEN", ""))
    shop_id: str = field(default_factory=lambda: os.getenv("SHOP_ID", "agent-service"))
    generation_concurrency: int = field(
        default_factory=lambda: int(os.getenv("AGENT_GENERATION_CONCURRENCY", "4"))
    )
    question_concurrency: int = field(
        default_factory=lambda: int(os.getenv("AGENT_QUESTION_CONCURRENCY", "2"))
    )
    tool_concurrency: int = field(
        default_factory=lambda: int(os.getenv("AGENT_TOOL_CONCURRENCY", "3"))
    )
    llm_url: str = field(default_factory=lambda: os.getenv("LLM_BASE_URL", ""))
    llm_model: str = field(default_factory=lambda: os.getenv("LLM_MODEL", ""))
    llm_key: str = field(default_factory=lambda: os.getenv("LLM_API_KEY", ""), repr=False)
    llm_max_rounds: int = field(default_factory=lambda: int(os.getenv("LLM_MAX_ROUNDS", "4")))
    agent_timeout: float = field(
        default_factory=lambda: float(os.getenv("AGENT_TOTAL_TIMEOUT", "70"))
    )
    nlu_enabled: bool = True
    handoff_terms: tuple[str, ...] = field(
        default_factory=lambda: tuple(
            term.strip() for term in os.getenv("AGENT_HANDOFF_TERMS", "").split(",") if term.strip()
        )
    )

    @property
    def model_configured(self):
        return bool(self.llm_url and self.llm_model and self.llm_key)

    def validate(self):
        if any(
            not 1 <= value <= 32
            for value in (
                self.generation_concurrency,
                self.question_concurrency,
                self.tool_concurrency,
            )
        ):
            raise ValueError("Agent concurrency values must be 1..32")
        if len(self.handoff_terms) > 50 or any(len(term) > 100 for term in self.handoff_terms):
            raise ValueError(
                "AGENT_HANDOFF_TERMS supports up to 50 terms of at most 100 characters"
            )
        model_values = [self.llm_url, self.llm_model, self.llm_key]
        if any(model_values) and not all(model_values):
            raise ValueError("Set LLM_BASE_URL, LLM_MODEL and LLM_API_KEY together")
        if not 1 <= self.llm_max_rounds <= 8 or not 5 <= self.agent_timeout <= 75:
            raise ValueError(
                "LLM_MAX_ROUNDS must be 1..8; AGENT_TOTAL_TIMEOUT must be 5..75 seconds"
            )
        if self.model_configured:
            url = urlsplit(self.llm_url)
            local_http = url.scheme == "http" and url.hostname in {"localhost", "127.0.0.1", "::1"}
            if (
                (url.scheme != "https" and not local_http)
                or not url.hostname
                or url.username
                or url.password
                or url.query
                or url.fragment
            ):
                raise ValueError("Use an HTTPS model base URL, or loopback HTTP for a local model")
        if len(self.token) < 24:
            raise ValueError("Configure INTERNAL_TOKEN (at least 24 characters)")
