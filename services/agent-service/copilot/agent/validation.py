"""Validate citations, scope and reply content; request bounded recovery when needed."""

import json

from pydantic import ValidationError

from .context import non_factual_scope
from .contracts import DraftAnswer, SocialVerification, Verification
from .prompts import SOCIAL_VERIFY_PROMPT, SYSTEM_PROMPT, VERIFY_PROMPT
from .understanding import intent_history


def build_validator(
    *, context, tools, client, settings, guidelines, verification, recover, record_call, scope
):
    async def finalize_answer(state):
        social_only, inventory_question = scope()
        if tools.needs_human:
            return {
                "answer": {
                    "outcome": "needs_human",
                    "content": "该咨询需人工核实处理。",
                    "citations": [],
                    "risk_codes": ["AGENT_HANDOFF"],
                }
            }
        if tools.blocked_error:
            return {
                "answer": {
                    "outcome": "needs_human",
                    "content": tools.missing_message(),
                    "citations": [],
                    "risk_codes": [tools.blocked_error],
                }
            }
        if state.get("calls"):
            # Finish the tool protocol even at the bound; never leave dangling
            # assistant tool calls in a provider request.
            messages = [
                *state["messages"],
                *[
                    {
                        "role": "tool",
                        "tool_call_id": call.id,
                        "content": '{"error":"STEP_LIMIT_REACHED"}',
                    }
                    for call in state["calls"]
                ],
                {
                    "role": "user",
                    "content": "工具调用已达上限，请只根据现有证据返回最终 JSON。",
                },
            ]
            record_call()
            response = await client.chat(messages, use_tools=False)
            if response.tool_calls:
                raise ValueError("AGENT_STEP_LIMIT")
            raw = response.content
            final_messages = [*messages, response.message()]
        else:
            raw = state["messages"][-1]["content"]
            final_messages = state["messages"]
        try:
            answer = DraftAnswer.model_validate_json(raw or "")
        except ValidationError:
            # Re-request the contract once; never extract a plausible JSON
            # substring and bypass strict validation or evidence checking.
            verification["format_repair_attempted"] = True
            record_call()
            response = await client.chat(
                [
                    *final_messages,
                    {
                        "role": "user",
                        "content": "上一条最终回答不符合约定的 JSON 格式。请仅输出一个 JSON 对象，字段为 outcome、content、evidence_ids、product_card_ids；自然语言只能放在 content 字段，不要添加前后说明或代码块。只使用已经返回的证据和 card_options 中的商品 ID，不新增事实或引用，不调用工具。有无或选购咨询有合适卡片时选择1–2款，否则 product_card_ids 为空数组。资料不足仍须标记 insufficient_evidence。",
                    },
                ],
                use_tools=False,
            )
            if response.tool_calls:
                raise ValueError("AGENT_STEP_LIMIT")
            answer = DraftAnswer.model_validate_json(response.content or "")
        if answer.outcome != "draft":
            if (
                answer.outcome == "insufficient_evidence"
                and not tools.trace
                and not social_only
                and not context.get("understanding", {}).get("needs_clarification")
            ):
                recovery = recover(state, ["missing_answer", "missing_evidence"])
                if recovery:
                    return recovery
            # Avoid presenting an ungrounded model answer as a handoff notice.
            return {
                "answer": {
                    "outcome": "needs_human"
                    if context.get("understanding", {}).get("requires_order")
                    else answer.outcome,
                    "content": "该咨询需要人工核实处理。"
                    if answer.outcome == "needs_human"
                    else tools.missing_message(),
                    "citations": [],
                    "risk_codes": [
                        "AGENT_HANDOFF"
                        if answer.outcome == "needs_human"
                        else "NO_VERIFIED_EVIDENCE"
                    ],
                }
            }
        if (not answer.evidence_ids and not social_only) or any(
            key not in tools.evidence for key in answer.evidence_ids
        ):
            if (
                not social_only
                and state.get("citation_retries", 0) < 1
                and state.get("rounds", 0) < settings.llm_max_rounds
            ):
                verification["citation_recovery_attempted"] = True
                recovery = recover({**state, "messages": final_messages}, ["citation_missing"])
                if recovery:
                    return {
                        **recovery,
                        "citation_retries": state.get("citation_retries", 0) + 1,
                    }
            return {
                "answer": {
                    "outcome": "needs_human",
                    "content": "回复引用未能与本次查询结果对应，请人工核实。",
                    "citations": [],
                    "risk_codes": ["INVALID_MODEL_CITATIONS"],
                }
            }
        if social_only and (answer.evidence_ids or answer.product_card_ids):
            raise ValueError("DRAFT_NOT_SUPPORTED")
        cards = tools.resolve_cards(answer)
        citations = [tools.evidence[key] for key in dict.fromkeys(answer.evidence_ids)]
        repair_evidence_ids = list(dict.fromkeys(answer.evidence_ids))
        if context.get("understanding", {}).get("requires_order") and not any(
            c["source_type"] == "platform_order" for c in citations
        ):
            recovery = recover(state, ["missing_order_evidence"])
            if recovery:
                return recovery
            return {
                "answer": {
                    "outcome": "insufficient_evidence",
                    "content": "未取得本次问题所需的订单依据，请人工核实。",
                    "citations": [],
                    "risk_codes": ["ORDER_FACTS_REQUIRED"],
                }
            }
        missing_inventory = inventory_question and not tools.has_inventory_evidence(
            answer.evidence_ids
        )
        if missing_inventory and not tools.has_inventory_evidence(list(tools.evidence)):
            recovery = recover(state, ["missing_inventory_evidence"])
            if recovery:
                return recovery
            return {
                "answer": {
                    "outcome": "insufficient_evidence",
                    "content": "本次未取得该商品的实时库存，请恢复商品查询后重试或人工核实；每件数量不能代替当前库存。",
                    "citations": [],
                    "risk_codes": ["LIVE_INVENTORY_REQUIRED"],
                }
            }

        async def check(candidate, sources):
            record_call()
            response = await client.chat(
                [
                    {
                        "role": "system",
                        "content": SOCIAL_VERIFY_PROMPT if social_only else VERIFY_PROMPT,
                    },
                    {
                        "role": "user",
                        "content": json.dumps(
                            {
                                "question": context["message"]["content"],
                                "review_scope": verification["scope"],
                                "question_intent": "inventory"
                                if inventory_question
                                else "unspecified",
                                "question_intents": context.get("understanding", {}).get(
                                    "intents", []
                                ),
                                "understanding": context.get("understanding", {}),
                                "history": intent_history(context) if social_only else [],
                                "draft": candidate.content,
                                "product_cards": [
                                    tools.card_options[key]["card"]
                                    for key in candidate.product_card_ids
                                ],
                                "citations": sources,
                                "response_guidelines": guidelines,
                            },
                            ensure_ascii=False,
                        ),
                    },
                ],
                use_tools=False,
            )
            if response.tool_calls:
                raise ValueError("VERIFICATION_FAILED")
            try:
                report = (SocialVerification if social_only else Verification).model_validate_json(
                    response.content or ""
                )
            except Exception:
                raise ValueError("VERIFICATION_FAILED") from None
            supported = report.supported and not report.issues
            issues = report.issues or ([] if supported else ["other"])
            scope = {}
            if social_only:
                scope = {
                    key: getattr(report, key)
                    for key in (
                        "question_is_social",
                        "contains_business_claims",
                        "contains_action_commitment",
                    )
                }
                if not report.question_is_social:
                    issues = list(dict.fromkeys([*issues, "missing_answer"]))
                if report.contains_business_claims:
                    issues = list(dict.fromkeys([*issues, "business_claims"]))
                if report.contains_action_commitment:
                    issues = list(dict.fromkeys([*issues, "unsupported_promise"]))
                supported = (
                    supported
                    and report.question_is_social
                    and not report.contains_business_claims
                    and not report.contains_action_commitment
                )
            verification["checks"].append({"supported": supported, "issues": issues, **scope})
            if social_only and not report.question_is_social and not settings.nlu_enabled:
                raise ValueError("SOCIAL_INTENT_CONFLICT")
            return supported

        if missing_inventory:
            # A successful query can still be omitted by the model. Repair
            # with already fetched, same-product stock; never invent a source.
            repair_evidence_ids.extend(
                key
                for key in tools.evidence
                if key not in repair_evidence_ids and tools.has_inventory_evidence([key])
            )
            verification["checks"].append({"supported": False, "issues": ["citation_missing"]})
            needs_repair = True
        else:
            needs_repair = not await check(answer, citations)
        if needs_repair:
            issues = verification["checks"][-1]["issues"]
            if settings.nlu_enabled and any(
                issue not in {"response_guideline", "reply_verbosity"} for issue in issues
            ):
                recovery = recover(state, issues)
                if recovery:
                    return recovery
            verification["repair_attempted"] = True
            record_call()
            repair = await client.chat(
                [
                    {
                        "role": "system",
                        "content": SYSTEM_PROMPT
                        + non_factual_scope(context)
                        + "\n仅修正下方草稿：只用所给证据，回答当前问题，处理检查原因并保留必要条件。不得调用工具或声称已执行操作；依据不足时返回 insufficient_evidence。保持原 JSON 结构。",
                    },
                    {
                        "role": "user",
                        "content": json.dumps(
                            {
                                "question": context["message"]["content"],
                                "question_intent": "inventory"
                                if inventory_question
                                else "unspecified",
                                "question_intents": context.get("understanding", {}).get(
                                    "intents", []
                                ),
                                "history": intent_history(context) if social_only else [],
                                "draft": answer.content,
                                "product_card_ids": answer.product_card_ids,
                                "card_options": [
                                    tools.card_options[key]["card"]
                                    for key in answer.product_card_ids
                                ],
                                "issues": verification["checks"][-1]["issues"],
                                "response_guidelines": guidelines,
                                "evidence": [
                                    {"evidence_id": key, **tools.evidence[key]}
                                    for key in repair_evidence_ids
                                ],
                            },
                            ensure_ascii=False,
                        ),
                    },
                ],
                use_tools=False,
            )
            if repair.tool_calls:
                raise ValueError("DRAFT_NOT_SUPPORTED")
            revised = DraftAnswer.model_validate_json(repair.content or "")
            if revised.outcome != "draft":
                return {
                    "answer": {
                        "outcome": revised.outcome,
                        "content": tools.missing_message()
                        if revised.outcome == "insufficient_evidence"
                        else "这条咨询需要人工核实处理。",
                        "citations": [],
                        "risk_codes": [
                            "NO_VERIFIED_EVIDENCE"
                            if revised.outcome == "insufficient_evidence"
                            else "AGENT_HANDOFF"
                        ],
                    }
                }
            if (not revised.evidence_ids and not social_only) or any(
                key not in repair_evidence_ids for key in revised.evidence_ids
            ):
                raise ValueError("INVALID_MODEL_CITATIONS")
            if social_only and (revised.evidence_ids or revised.product_card_ids):
                raise ValueError("DRAFT_NOT_SUPPORTED")
            cards = tools.resolve_cards(revised)
            if inventory_question and not tools.has_inventory_evidence(revised.evidence_ids):
                raise ValueError("DRAFT_NOT_SUPPORTED")
            if context.get("understanding", {}).get("requires_order") and not any(
                tools.evidence[key]["source_type"] == "platform_order"
                for key in revised.evidence_ids
            ):
                raise ValueError("DRAFT_NOT_SUPPORTED")
            answer = revised
            citations = [tools.evidence[key] for key in dict.fromkeys(answer.evidence_ids)]
            if not await check(answer, citations):
                raise ValueError("DRAFT_NOT_SUPPORTED")
        return {
            "answer": {
                "outcome": "draft",
                "content": answer.content,
                "citations": citations,
                "product_cards": cards,
                "risk_codes": [],
            }
        }

    return finalize_answer
