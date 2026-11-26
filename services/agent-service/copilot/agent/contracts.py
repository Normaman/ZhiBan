"""Strict model output contracts and in-request graph state."""

from typing import Literal, TypedDict

from pydantic import BaseModel, ConfigDict, Field


class DraftAnswer(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    outcome: str = Field(pattern="^(draft|needs_human|insufficient_evidence)$")
    content: str = Field(min_length=1, max_length=4000)
    evidence_ids: list[str] = Field(default_factory=list, max_length=20)
    product_card_ids: list[str] = Field(default_factory=list, max_length=3)


class Verification(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    supported: bool
    issues: list[
        Literal[
            "price_scope",
            "price_value",
            "stock_value",
            "spec_identity",
            "minimum_comparison",
            "unsupported_promise",
            "missing_answer",
            "citation_missing",
            "response_guideline",
            "reply_verbosity",
            "other",
        ]
    ] = Field(default_factory=list, max_length=12)


class SocialVerification(Verification):
    issues: list[
        Literal[
            "response_guideline",
            "reply_verbosity",
            "business_claims",
            "unsupported_promise",
            "missing_answer",
            "other",
        ]
    ] = Field(default_factory=list, max_length=6)
    question_is_social: bool
    contains_business_claims: bool
    contains_action_commitment: bool


class AgentState(TypedDict, total=False):
    messages: list[dict]
    calls: list
    rounds: int
    citation_retries: int
    replan_requested: bool
    recovery_issues: list[str]
    answer: dict
