"""Editable, normalized evaluation facts. These adapters never contact a platform."""

from copy import deepcopy
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class Provenance(BaseModel):
    model_config = ConfigDict(extra="forbid")
    kind: Literal["synthetic", "anonymized_real", "public_reference"] = "synthetic"
    reference: str = Field(default="", max_length=2000)
    group_id: str = Field(default="", max_length=120)


class FixtureContext(BaseModel):
    model_config = ConfigDict(extra="forbid")
    buyer_ref: str = Field(default="synthetic-buyer", min_length=1, max_length=200)
    knowledge: list[dict[str, Any]] = Field(default_factory=list, max_length=500)
    history: list[dict[str, Any]] = Field(default_factory=list, max_length=100)
    product_context: dict[str, Any] = Field(default_factory=dict)
    platform_product_facts: dict[str, Any] = Field(default_factory=dict)
    order_reference: dict[str, Any] = Field(default_factory=dict)
    questions: list[dict[str, Any]] = Field(default_factory=list, max_length=8)
    question_contexts: dict[str, Any] = Field(default_factory=dict)
    question_orders: dict[str, Any] = Field(default_factory=dict)


class ScenarioData(BaseModel):
    model_config = ConfigDict(extra="forbid")
    context: FixtureContext = Field(default_factory=FixtureContext)
    products: list[dict[str, Any]] = Field(default_factory=list, max_length=1000)
    product_details: dict[str, dict[str, Any]] = Field(default_factory=dict)
    orders: list[dict[str, Any]] = Field(default_factory=list, max_length=100)

    @model_validator(mode="after")
    def validate_relationships(self):
        for entry in self.context.knowledge:
            if not all(
                isinstance(entry.get(k), str) and entry[k].strip()
                for k in ["source_id", "source_type", "text"]
            ):
                raise ValueError("知识条目必须包含来源编号、类型和正文")
            if not isinstance(entry.get("topics", []), list):
                raise ValueError("知识分类 topics 必须为列表")
        for item in self.context.history:
            if not isinstance(item.get("content"), str):
                raise ValueError("历史消息必须有正文 content")
        for item in [
            *self.products,
            *(s for d in self.product_details.values() for s in d.get("skus", [])),
        ]:
            for name in ["quantity", "group_price_cents", "single_price_cents"]:
                value = item.get(name)
                if value is not None and (type(value) is not int or value < 0):
                    raise ValueError("库存及分为单位的价格须为非负整数或 null")
        goods = [str(p.get("goods_id", "")) for p in self.products]
        if any(not g for g in goods) or len(set(goods)) != len(goods):
            raise ValueError("商品编号必须非空且唯一")
        selected = self.context.product_context.get("goods_id")
        if selected and str(selected) not in goods:
            raise ValueError("当前商品必须存在于测试商品列表")
        for key, detail in self.product_details.items():
            if key not in goods or str(detail.get("goods_id", "")) != key:
                raise ValueError("规格详情必须绑定同编号测试商品")
        order_ids = [str(o.get("order_id", "")) for o in self.orders]
        if any(not v for v in order_ids) or len(set(order_ids)) != len(order_ids):
            raise ValueError("测试订单编号必须非空且唯一")
        for order in self.orders:
            if order.get("buyer_ref") != self.context.buyer_ref:
                raise ValueError("测试订单买家必须与测试上下文一致")
        questions = self.context.questions
        if questions and (
            any(not q.get("id") or not q.get("content") for q in questions)
            or len({q["id"] for q in questions}) != len(questions)
        ):
            raise ValueError("咨询批次须有非空、唯一编号和问题正文")
        return self


def context_for(case, fixtures_module=None):
    if fixtures_module is None:
        from evals import fixtures as fixtures_module

    if case.input is None:
        return fixtures_module.context_for(case)
    value = deepcopy(case.input.context.model_dump())
    questions = value.pop("questions") or [{"id": "q1", "content": case.question}]
    if questions[-1]["content"] != case.question:
        raise ValueError("批次最后一条问题须与用例问题一致")
    anchor = questions[-1]
    return {
        **value,
        "id": "eval-" + case.id,
        "message_id": anchor["id"],
        "conversation_revision": 1,
        "shop_id": "synthetic-shop",
        "knowledge_version": fixtures_module.VERSION,
        "message": {
            "id": anchor["id"],
            "content": case.question,
            "message_type": anchor.get("message_type", "text"),
        },
        "pending_questions": [
            {
                "message_id": q["id"],
                "content": q["content"],
                "message_type": q.get("message_type", "text"),
            }
            for q in questions
        ],
    }


class DataConnector:
    def __init__(self, data):
        self.data = data
        self.calls = []

    async def products(self):
        from evals.fixtures import AT

        self.calls.append({"method": "products"})
        return [{"fetched_at": AT, **deepcopy(p)} for p in self.data.products]

    async def product_detail(self, goods, **kwargs):
        from copilot.business_errors import BusinessToolError

        from evals.fixtures import AT

        self.calls.append({"method": "product_detail", "goods_id": goods})
        if goods not in self.data.product_details:
            raise BusinessToolError("PRODUCT_NOT_FOUND")
        return {"fetched_at": AT, **deepcopy(self.data.product_details[goods])}

    async def order_detail(self, order_id, buyer):
        from copilot.business_errors import BusinessToolError

        from evals.fixtures import AT

        self.calls.append({"method": "order_detail", "order_id": order_id})
        matches = [
            o for o in self.data.orders if o["order_id"] == order_id and o["buyer_ref"] == buyer
        ]
        if len(matches) != 1:
            raise BusinessToolError("ORDER_BUYER_MISMATCH")
        return {"fetched_at": AT, "missing_fields": [], **deepcopy(matches[0])}


def connector_for(case, fixtures_module=None):
    if fixtures_module is None:
        from evals import fixtures as fixtures_module

    return DataConnector(case.input) if case.input is not None else fixtures_module.FixtureConnector(case.fixture)


async def editable_data(case, fixtures_module=None):
    """Materialize an existing synthetic fixture for editing, without a model."""
    from copilot.business_errors import BusinessToolError

    if case.input is not None:
        return case.input
    context = context_for(case, fixtures_module)
    connector = connector_for(case, fixtures_module)
    products = await connector.products()
    details = {}
    for p in products:
        try:
            details[str(p["goods_id"])] = await connector.product_detail(str(p["goods_id"]))
        except BusinessToolError:
            pass
    orders = []
    if context.get("order_reference", {}).get("order_id"):
        orders.append(
            await connector.order_detail(
                context["order_reference"]["order_id"], context["buyer_ref"]
            )
        )
    fields = {k: context[k] for k in FixtureContext.model_fields if k in context}
    if context.get("pending_questions"):
        fields["questions"] = [
            {"id": q["message_id"], "content": q["content"], "message_type": q["message_type"]}
            for q in context["pending_questions"]
        ]
    return ScenarioData(
        context=FixtureContext(**fields), products=products, product_details=details, orders=orders
    )
