"""Risk gate plus a model/tool subgraph, or explicit model-free evidence extraction."""

import asyncio
import re
import time
from typing import TypedDict

from langgraph.graph import END, START, StateGraph

from .agent.batch import generate_batch
from .agent.intent import asks_inventory
from .agent.runner import ModelAgent
from .agent.understanding import analyze, normalized, prohibited_reply, risk_flags
from .knowledge.guidelines import response_guidelines
from .knowledge.hybrid import search

TOPICS = [
    ("shipping", r"发货|到货|多久|什么时候|物流|配送|ship|deliver"),
    ("inventory", r"库存|现货|有货|stock"),
    ("policy", r"售后|退换|无理由|保修|policy|warranty"),
    ("recommendation", r"推荐|价格|多少钱|买哪|recommend|price"),
    ("spec", r"规格|尺寸|材质|容量|成分|参数|怎么用|spec|size|material"),
]


class State(TypedDict, total=False):
    context: dict
    topic: str
    risk_codes: list[str]
    evidence: list[dict]
    outcome: str
    content: str
    engine: str
    model: str
    tool_trace: list[dict]
    model_calls: int
    history_truncated: bool
    verification: dict
    understanding: dict
    order_context: dict
    product_cards: list[dict]


class DraftGraph:
    def __init__(self, settings, connector):
        self.settings = settings
        self.connector = connector
        self.agent = ModelAgent(settings, connector)
        graph = StateGraph(State)
        graph.add_node("policy_gate", self.gate)
        graph.add_node("understand", self.understand)
        graph.add_node("retrieve", self.retrieve)
        graph.add_node("compose", self.compose)
        graph.add_node("model_agent", self.run_model)
        graph.add_edge(START, "policy_gate")
        graph.add_conditional_edges(
            "policy_gate",
            lambda s: (
                "compose"
                if s.get("outcome")
                else "understand"
                if settings.model_configured
                else "retrieve"
            ),
        )
        graph.add_conditional_edges(
            "understand", lambda s: "compose" if s.get("outcome") else "model_agent"
        )
        graph.add_edge("retrieve", "compose")
        graph.add_edge("compose", END)
        graph.add_edge("model_agent", END)
        self.graph = graph.compile()

    def gate(self, state):
        context = state["context"]
        msg = context["message"]
        history = (
            ""
            if "pending_questions" in context
            else "\n".join(
                m["content"]
                for m in context.get("history", [])
                if m["direction"] == "inbound" and m.get("message_type") != "order_info"
            )
        )
        text = msg["content"]
        pending = context.get("pending_questions", [])
        if len(pending) > 8 or sum(len(q["content"]) for q in pending) > 12000:
            return {
                "outcome": "needs_human",
                "risk_codes": ["QUESTION_BATCH_TOO_LARGE"],
                "evidence": [],
            }
        if any(
            q.get("message_type")
            not in {"text", "emotion", "goods_inquiry", "goods_spec", "order_info"}
            for q in pending
        ):
            return {
                "outcome": "needs_human",
                "risk_codes": ["UNSUPPORTED_MESSAGE_TYPE"],
                "evidence": [],
            }
        if len(pending) > 1 and not self.settings.model_configured:
            return {
                "outcome": "needs_human",
                "risk_codes": ["MULTI_QUESTION_MODEL_REQUIRED"],
                "evidence": [],
            }
        buyer_text = "" if msg["message_type"] == "order_info" else text
        flags = risk_flags(buyer_text + "\n" + history)
        if any(normalized(term) in normalized(buyer_text) for term in self.settings.handoff_terms):
            flags.append("configured_handoff")
        if flags and (
            not self.settings.model_configured
            or not self.settings.nlu_enabled
            or "configured_handoff" in flags
        ):
            return {
                "outcome": "needs_human",
                "risk_codes": ["MANDATORY_HANDOFF"],
                "evidence": [],
                "understanding": {"source": "safety_rules", "risk_flags": flags},
            }
        guidelines = response_guidelines(context)
        if len(guidelines) > 30 or sum(len(g["evidence_text"]) for g in guidelines) > 16000:
            return {
                "outcome": "needs_human",
                "risk_codes": ["RESPONSE_GUIDELINES_TOO_LONG"],
                "evidence": [],
            }
        if context.get("product_context", {}).get("ambiguous") and not (
            self.settings.model_configured and self.settings.nlu_enabled
        ):
            return {
                "outcome": "needs_human",
                "risk_codes": ["PRODUCT_CONTEXT_AMBIGUOUS"],
                "evidence": [],
            }
        if response_guidelines(context) and not self.settings.model_configured:
            return {
                "outcome": "needs_human",
                "risk_codes": ["RESPONSE_GUIDELINES_REQUIRE_MODEL"],
                "evidence": [],
            }
        if msg["message_type"] not in {
            "text",
            "emotion",
            "goods_inquiry",
            "goods_spec",
            "order_info",
        }:
            return {
                "outcome": "needs_human",
                "risk_codes": ["UNSUPPORTED_MESSAGE_TYPE"],
                "evidence": [],
            }
        topic = (
            "inventory"
            if asks_inventory(text)
            else next((name for name, pattern in TOPICS if re.search(pattern, text, re.I)), None)
        )
        if topic is None and not self.settings.model_configured:
            return {
                "outcome": "insufficient_evidence",
                "risk_codes": ["INTENT_UNCLEAR"],
                "evidence": [],
            }
        return {"topic": topic or "unknown", "risk_codes": []}

    async def understand(self, state):
        if not self.settings.nlu_enabled:
            return {}
        try:
            understanding = await analyze(state["context"], self.agent.client)
        except Exception:
            return {
                "outcome": "needs_human",
                "risk_codes": ["INTENT_ANALYSIS_FAILED"],
                "evidence": [],
                "understanding": {"source": "model", "failed": True},
            }
        context = {**state["context"], "understanding": understanding}
        if understanding.get("product_scope") == "catalog" and understanding.get(
            "product_keywords"
        ):
            context["product_context"] = {}
            context["platform_product_facts"] = {}
        if understanding["risk_flags"] or "after_sales_action" in understanding["intents"]:
            return {
                "context": context,
                "understanding": understanding,
                "outcome": "needs_human",
                "risk_codes": ["MANDATORY_HANDOFF"],
                "evidence": [],
            }
        order = context.get("order_context", {})
        if context["message"]["message_type"] == "order_info":
            return {
                "context": context,
                "understanding": understanding,
                "outcome": "insufficient_evidence",
                "risk_codes": ["INTENT_UNCLEAR"],
                "evidence": [],
                "order_context": order,
            }
        return {"context": context, "understanding": understanding, "order_context": order}

    async def run_model(self, state):
        result = await self.agent.generate(state["context"])
        return {**result, "evidence": result["citations"]}

    async def retrieve(self, state):
        context = state["context"]
        topic = state["topic"]
        text = context["message"]["content"]
        evidence = []
        retrieval_trace = []
        if topic in {"inventory", "recommendation"}:
            try:
                products = await self.connector.products()
            except Exception:
                return {
                    "outcome": "insufficient_evidence",
                    "evidence": [],
                    "risk_codes": ["LIVE_PRODUCT_QUERY_FAILED"],
                }
            # Prefer an explicit ID/name. Never assert a particular item's stock from another item.
            matching = [
                p
                for p in products
                if str(p.get("goods_id", "")) in text
                or (p.get("goods_name") and p["goods_name"] in text)
            ]
            context_id = context.get("product_context", {}).get("goods_id")
            if (
                not matching
                and context_id
                and re.fullmatch(
                    r"(?:这款|这个|该商品|这件商品|现在|目前|请问|还有|有|的|是|吗|呢|啊|？|\?|库存|现货|有货|多少|剩余|剩|几|张|件|个|枚|片|包|盒|套|袋|本|条|双|瓶|支|卷|数量|还有货|\s)+",
                    text,
                )
            ):
                matching = [p for p in products if str(p.get("goods_id")) == context_id]
            if topic == "inventory" and not matching and len(products) != 1:
                return {
                    "outcome": "insufficient_evidence",
                    "evidence": [],
                    "risk_codes": ["PRODUCT_AMBIGUOUS"],
                }
            for p in (matching or products)[:5]:
                facts = [p.get("goods_name", "")]
                if p.get("price") is not None:
                    facts.append("接口显示价格：" + str(p["price"]) + " 元")
                if p.get("quantity") is not None:
                    facts.append("接口显示库存：" + str(p["quantity"]))
                elif topic == "inventory":
                    continue
                evidence.append(
                    {
                        "source_type": "platform_product",
                        "source_id": str(p["goods_id"]),
                        "source_version": p["fetched_at"],
                        "fetched_at": p["fetched_at"],
                        "evidence_text": "；".join(facts),
                    }
                )
        else:
            entries = [e for e in context.get("knowledge", []) if topic in e.get("topics", [])]
            if topic == "spec":
                matching = [e for e in entries if e.get("goods_id") and str(e["goods_id"]) in text]
                if matching:
                    entries = matching
                elif context.get("product_context", {}).get("goods_id"):
                    entries = [
                        e
                        for e in entries
                        if str(e.get("goods_id")) == context["product_context"]["goods_id"]
                    ]
                elif len(entries) > 1:
                    return {
                        "outcome": "insufficient_evidence",
                        "evidence": [],
                        "risk_codes": ["PRODUCT_AMBIGUOUS"],
                    }
            retrieval_started = time.monotonic()
            ranked, retrieval = await search(context, entries, text)
            retrieval_trace = [
                {
                    "name": "search_customer_service_knowledge",
                    "status": "ok",
                    "duration_ms": int((time.monotonic() - retrieval_started) * 1000),
                    "retrieval": retrieval,
                }
            ]
            entries = ranked or (
                entries if retrieval["mode"] != "hybrid" and len(entries) <= 5 else []
            )
            for e in entries[:5]:
                if topic == "spec" and e["source_type"] != "product":
                    continue
                if topic in {"shipping", "policy"} and e["source_type"] != "shop_policy":
                    continue
                evidence.append(
                    {
                        "source_type": e["source_type"],
                        "source_id": e["source_id"],
                        "source_version": context["knowledge_version"],
                        "evidence_text": e["text"],
                        **{
                            key: e[key]
                            for key in ("document_name", "source_location", "title")
                            if key in e
                        },
                    }
                )
        return {
            "evidence": evidence,
            "tool_trace": retrieval_trace,
            "outcome": "draft" if evidence else "insufficient_evidence",
            "risk_codes": [] if evidence else ["NO_VERIFIED_EVIDENCE"],
        }

    async def compose(self, state):
        outcome = state.get("outcome", "insufficient_evidence")
        clarification = (
            "还不能确定数量问题指的是当前库存还是每份包含数量，请人工确认。"
            if state.get("understanding", {}).get("quantity_meaning") == "unclear"
            else "暂时没能确定这条消息的具体诉求，请人工核实。"
        )
        if outcome != "draft":
            return {
                "content": "订单归属已核对，但平台本次未返回所需的发货、物流或售后状态，请人工核实。"
                if "ORDER_DETAILS_INCOMPLETE" in state.get("risk_codes", [])
                else "已核对订单归属，请说明要咨询的订单问题。"
                if state.get("order_context") and "INTENT_UNCLEAR" in state.get("risk_codes", [])
                else "需要核对该买家的订单事实，请关联订单后查询；不能按通用规则猜测订单进度。"
                if "ORDER_FACTS_REQUIRED" in state.get("risk_codes", [])
                else clarification
                if "INTENT_UNCLEAR" in state.get("risk_codes", [])
                else "该咨询需要人工核实处理。"
                if outcome == "needs_human"
                else "当前依据不足，请补充商品信息或转人工核实。",
                "engine": "llm_agent"
                if state.get("understanding", {}).get("source") == "model"
                else "policy",
                "model": self.settings.llm_model
                if state.get("understanding", {}).get("source") == "model"
                else None,
            }
        evidence = state["evidence"]
        engine = "extractive"
        content = "您好，查询到以下资料：\n" + "\n".join(e["evidence_text"] for e in evidence)
        if len(content) > 4000:
            return {
                "content": "资料较长，请人工查看依据后回复。",
                "outcome": "needs_human",
                "engine": "policy",
                "risk_codes": ["EVIDENCE_TOO_LONG"],
            }
        return {"content": content, "evidence": evidence, "engine": engine}

    async def _generate_single(self, context):
        pending = context.get("pending_questions", [])
        if pending:
            context = {
                **context,
                "message": {
                    **context["message"],
                    "content": pending[0]["content"]
                    if len(pending) == 1
                    else "\n".join(f"{i + 1}. {q['content']}" for i, q in enumerate(pending)),
                },
            }
        try:
            state = await asyncio.wait_for(
                self.graph.ainvoke({"context": context}), timeout=self.settings.agent_timeout
            )
        except asyncio.TimeoutError:
            state = {
                "outcome": "needs_human",
                "content": "本次处理超时，请重试或人工核实。",
                "engine": "policy",
                "risk_codes": ["MODEL_TIMEOUT"],
            }
        if state["outcome"] == "draft" and prohibited_reply(state["content"]):
            state = {
                **state,
                "outcome": "needs_human",
                "content": "草稿含不允许的引流、评价激励或越权承诺，请人工核实。",
                "evidence": [],
                "risk_codes": prohibited_reply(state["content"]),
            }
        return {
            "run_id": context["id"],
            "message_id": context["message_id"],
            "conversation_revision": context["conversation_revision"],
            "knowledge_version": context.get("knowledge_version"),
            "outcome": state["outcome"],
            "content": state["content"],
            "citations": state.get("evidence", []),
            "risk_codes": state.get("risk_codes", []),
            "engine": state["engine"],
            "model": state.get("model"),
            "tool_trace": state.get("tool_trace", []),
            "model_calls": state.get("model_calls", 0)
            + int(state.get("understanding", {}).get("source") == "model"),
            "understanding": state.get("understanding", {}),
            "order_context": state.get("context", {}).get(
                "order_context", state.get("order_context", {})
            ),
            "verification": state.get("verification", {}),
            "history_truncated": state.get("history_truncated", False),
            "product_cards": state.get("product_cards", []) if state["outcome"] == "draft" else [],
        }

    async def generate(self, context):
        return await generate_batch(context, self.settings, self._generate_single)
