"""Run bounded per-question drafts and preserve explicit answer coverage."""

import asyncio


async def generate_batch(context, settings, generate_single):
    pending = context.get("pending_questions", [])
    if (
        len(pending) <= 1
        or len(pending) > 8
        or sum(len(q["content"]) for q in pending) > 12000
        or not settings.model_configured
    ):
        result = await generate_single(context)
        if pending:
            result["answered_message_ids"] = (
                [q["message_id"] for q in pending] if result["outcome"] == "draft" else []
            )
            result["question_results"] = [
                {
                    "message_id": q["message_id"],
                    "outcome": result["outcome"],
                    "risk_codes": result.get("risk_codes", []),
                    "verification": result.get("verification", {}),
                    "understanding": result.get("understanding", {}),
                }
                for q in pending
            ]
        return result
    semaphore = asyncio.Semaphore(settings.question_concurrency)

    async def one(question):
        async with semaphore:
            product = context.get("question_contexts", {}).get(
                question["message_id"], context.get("product_context", {})
            )
            child = {
                **context,
                "message_id": question["message_id"],
                "pending_questions": [question],
                "product_context": product,
                "order_reference": context.get("question_orders", {}).get(
                    question["message_id"], {}
                ),
                "message": {
                    **context["message"],
                    "id": question["message_id"],
                    "content": question["content"],
                    "message_type": question["message_type"],
                },
            }
            if question["message_id"] in context.get("question_product_facts", {}):
                child["platform_product_facts"] = context["question_product_facts"][
                    question["message_id"]
                ]
            elif product.get("goods_id") != context.get("product_context", {}).get("goods_id"):
                child["platform_product_facts"] = {}
            return await generate_single(child)

    tasks = [asyncio.create_task(one(q)) for q in pending[:8]]
    try:
        await asyncio.wait(tasks, timeout=settings.agent_timeout)
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
        outcomes = await asyncio.gather(*tasks, return_exceptions=True)
    answers, citations, answered, results, trace, cards = [], [], [], [], [], []
    calls = 0
    for index, question in enumerate(pending):
        value = outcomes[index] if index < len(outcomes) else None
        if not isinstance(value, dict):
            value = {"outcome": "needs_human", "risk_codes": ["GENERATION_TIMEOUT"]}
        outcome = value["outcome"]
        calls += value.get("model_calls", 0)
        trace.extend(value.get("tool_trace", []))
        if (
            outcome == "draft"
            and sum(map(len, answers)) + len(value["content"]) + 2 * len(answers) <= 4000
        ):
            answers.append(value["content"])
            answered.append(question["message_id"])
            citations.extend(value.get("citations", []))
            cards.extend(value.get("product_cards", []))
        elif outcome == "draft":
            outcome = "needs_human"
        results.append(
            {
                "message_id": question["message_id"],
                "outcome": outcome,
                "risk_codes": value.get("risk_codes", []),
                "verification": value.get("verification", {}),
                "understanding": value.get("understanding", {}),
            }
        )
    return {
        "run_id": context["id"],
        "message_id": context["message_id"],
        "conversation_revision": context["conversation_revision"],
        "knowledge_version": context.get("knowledge_version"),
        "outcome": "draft" if answered else "needs_human",
        "content": "\n\n".join(answers)
        if answered
        else "本轮未生成可用回复。请查看下方每条咨询的处理原因，补充信息或单独重试。",
        "citations": list(
            {(c["source_type"], c["source_id"], c["source_version"]): c for c in citations}.values()
        ),
        "risk_codes": []
        if answered
        else list(
            dict.fromkeys(code for question in results for code in question.get("risk_codes", []))
        ),
        "engine": "llm_agent",
        "model": settings.llm_model,
        "tool_trace": trace,
        "model_calls": calls,
        "history_truncated": any(
            isinstance(value, dict) and value.get("history_truncated", False) for value in outcomes
        ),
        "answered_message_ids": answered,
        "question_results": results,
        "product_cards": list({c["goods_id"]: c for c in cards}.values())[:3],
    }
