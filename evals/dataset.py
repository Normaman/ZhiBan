"""Versioned synthetic scenarios and strict gold labels, separate from model inputs."""

import hashlib
import json
import re
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from evals.case_data import Provenance, ScenarioData


class Expected(BaseModel):
    model_config = ConfigDict(extra="forbid")
    outcomes: list[Literal["draft", "needs_human", "insufficient_evidence"]]
    intents: list[str] = Field(default_factory=list)
    intent_alternatives: list[list[str]] = Field(default_factory=list)
    product_scope: Literal["current", "catalog"] | None = None
    quantity_meaning: Literal["inventory", "unit_count", "both", "none", "unclear"] | None = None
    tools: list[str] = Field(default_factory=list)
    tool_alternatives: list[list[str]] = Field(default_factory=list)
    parameter_patterns: dict[str, dict[str, str]] = Field(default_factory=dict)
    sources: list[str] = Field(default_factory=list)
    platform_source_prefixes: list[str] = Field(default_factory=list)
    retrieval_sources: list[str] = Field(default_factory=list)
    history_truncated: bool | None = None
    contains: list[str] = Field(default_factory=list)
    forbids: list[str] = Field(default_factory=list)
    risk: bool = False
    answered: list[str] | None = None
    max_characters: int = Field(default=180, ge=1, le=4000)


class Case(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str = Field(pattern=r"^[a-z0-9_-]+$")
    scenario: str = Field(min_length=1, max_length=120)
    split: Literal["dev", "test"]
    fixture: str
    question: str = Field(min_length=1, max_length=2000)
    expected: Expected
    input: ScenarioData | None = None
    reference_answer: str = Field(default="", max_length=4000)
    provenance: Provenance = Field(default_factory=Provenance)


def load(path: Path):
    cases = [
        Case.model_validate_json(line) for line in path.read_text().splitlines() if line.strip()
    ]
    return validate_cases(cases)


def validate_cases(cases):
    if not cases or len({c.id for c in cases}) != len(cases):
        raise ValueError("EMPTY_OR_DUPLICATE_CASES")
    families = {}
    groups = {}
    for case in cases:
        if case.fixture not in {
            "inventory",
            "linked",
            "unit",
            "sku",
            "price",
            "shipping",
            "presale",
            "freight",
            "faq",
            "policy",
            "generic_shipping",
            "cancelled",
            "unpaid",
            "logistics",
            "risk",
            "social",
            "unknown_stock",
            "missing",
            "batch",
            "memory",
            "custom",
        }:
            raise ValueError("UNKNOWN_BUSINESS_FIXTURE")
        if (case.fixture == "custom") != (case.input is not None):
            raise ValueError("CUSTOM_FIXTURE_REQUIRES_EXPLICIT_INPUT")
        if case.provenance.group_id:
            prior_group = groups.setdefault(case.provenance.group_id, case.split)
            if prior_group != case.split:
                raise ValueError("CONVERSATION_GROUP_SPLIT_LEAKAGE")
        prior = families.setdefault(case.scenario, case.split)
        if prior != case.split:
            raise ValueError("SCENARIO_SPLIT_LEAKAGE")
        if not case.expected.outcomes:
            raise ValueError("EMPTY_EXPECTED_OUTCOMES")
        try:
            for pattern in [*case.expected.contains, *case.expected.forbids]:
                re.compile(pattern)
            for parameters in case.expected.parameter_patterns.values():
                for pattern in parameters.values():
                    re.compile(pattern)
        except re.error as error:
            raise ValueError(f"{case.id} 的检查表达式无效：{error}") from error
    return cases


def fingerprint(value):
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True).encode()
    ).hexdigest()
