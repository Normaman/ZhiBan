"""Frozen synthetic business inputs. Gold labels are never consumed here."""

from copy import deepcopy

from copilot.business_errors import BusinessToolError

AT = "2026-10-01T12:00:00+08:00"
VERSION = "synthetic-shop-v1"
PRODUCTS = [
    {"goods_id": "900000001", "goods_name": "法国纸币甲", "quantity": 21, "price": "12.00"},
    {"goods_id": "900000002", "goods_name": "波兰纸币乙", "quantity": 7, "price": "10.00"},
    {"goods_id": "900000003", "goods_name": "法国纸币丙", "quantity": 4, "price": "35.00"},
]
KNOWLEDGE = [
    {
        "source_id": "reception",
        "source_type": "shop_policy",
        "topics": ["response_guidelines"],
        "title": "客服接待规范",
        "text": "直接回答当前问题，通常1–3句；纯考虑和致谢自然回应，不强迫选购。"
        "示例不是业务事实，不编造库存、鉴定、赔偿或执行操作。",
    },
    {
        "source_id": "shipping",
        "source_type": "shop_policy",
        "topics": ["shipping"],
        "title": "通用发货规则",
        "text": "店铺通用现货付款后48小时内发货；具体商品预售和配送设置优先。",
    },
    {
        "source_id": "returns",
        "source_type": "shop_policy",
        "topics": ["policy"],
        "title": "七天退货条件",
        "text": "符合七天无理由适用范围、商品完好且不影响二次销售，"
        "可在订单页申请退货退款，工作人员审核；不代表已同意或执行退款。",
    },
    {
        "source_id": "authenticity",
        "source_type": "shop_policy",
        "topics": ["faq"],
        "title": "纸币保真问答",
        "text": "问题：纸币保真吗？回复模板：本店所售纸币保真。"
        "适用范围：本店纸币。此为商家说明，不是平台鉴定，没有额外赔偿或鉴定证书承诺。",
    },
    {
        "source_id": "unit-a",
        "source_type": "product",
        "goods_id": "900000001",
        "topics": ["spec"],
        "text": "法国纸币甲每件包含1张纸币。",
    },
    {
        "source_id": "unit-b",
        "source_type": "product",
        "goods_id": "900000002",
        "topics": ["spec"],
        "text": "波兰纸币乙每件包含2张纸币。",
    },
]


def detail(goods):
    return {
        "goods_id": goods,
        "goods_name": next(p["goods_name"] for p in PRODUCTS if p["goods_id"] == goods),
        "fetched_at": AT,
        "detail_schema_version": 3,
        "shipping": {
            "mode": "regular",
            "summary": "本商品付款后72小时内发货",
            "shipment_limit_seconds": 259200,
        },
        "freight": {
            "status": "synced",
            "free_regions": ["浙江", "上海"],
            "undeliverable_regions": ["新疆"],
            "paid_rules": [
                {
                    "regions": ["甘肃"],
                    "unit": "piece",
                    "first_units": 1,
                    "first_fee_cents": 1500,
                    "additional_units": 1,
                    "additional_fee_cents": 0,
                    "free_min_count": None,
                    "free_min_amount_cents": None,
                }
            ],
        },
        "sku_count": 3,
        "skus_truncated": False,
        "skus": [
            {
                "sku_id": "red",
                "specs": [{"name": "颜色", "value": "红色"}],
                "quantity": 5,
                "group_price_cents": 1200,
                "single_price_cents": 1400,
                "on_sale": True,
            },
            {
                "sku_id": "blue",
                "specs": [{"name": "颜色", "value": "蓝色"}],
                "quantity": 2,
                "group_price_cents": 1000,
                "single_price_cents": 1300,
                "on_sale": True,
            },
            {
                "sku_id": "green",
                "specs": [{"name": "颜色", "value": "绿色"}],
                "quantity": 0,
                "group_price_cents": 500,
                "single_price_cents": 700,
                "on_sale": True,
            },
        ],
    }


class FixtureConnector:
    """Only read operations exist; platform credentials and sender are never loaded."""

    def __init__(self, fixture):
        self.fixture = fixture
        self.calls = []

    async def products(self):
        self.calls.append({"method": "products"})
        rows = [{**p, "fetched_at": AT} for p in deepcopy(PRODUCTS)]
        if self.fixture == "unknown_stock":
            rows[1]["quantity"] = None
        return rows

    async def product_detail(self, goods):
        self.calls.append({"method": "product_detail", "goods_id": goods})
        if goods not in {p["goods_id"] for p in PRODUCTS}:
            raise BusinessToolError("PRODUCT_NOT_FOUND")
        value = detail(goods)
        if goods == "900000001":
            value["skus"][0]["quantity"] = 15
            value["skus"][1]["quantity"] = 6
        elif goods == "900000003":
            value["skus"][0]["quantity"] = 3
            value["skus"][1]["quantity"] = 1
        if self.fixture == "unknown_stock":
            for sku in value["skus"]:
                sku["quantity"] = None
        if self.fixture == "presale":
            value["shipping"] = {"mode": "presale", "summary": "本商品预售，2026年12月20日前发货"}
        return value

    async def order_detail(self, order_id, buyer):
        self.calls.append({"method": "order_detail", "order_id": order_id})
        if order_id != "synthetic-order" or buyer != "synthetic-buyer":
            raise BusinessToolError("ORDER_BUYER_MISMATCH")
        status = {"cancelled": "cancelled", "unpaid": "unpaid"}.get(self.fixture, "unknown")
        return {
            "order_id": order_id,
            "buyer_ref": buyer,
            "goods_id": "900000002",
            "goods_name": "波兰纸币乙",
            "purchased_quantity": 1,
            "fetched_at": AT,
            "order_status": status,
            "status_text": {"cancelled": "已取消", "unpaid": "待付款"}.get(
                self.fixture, "状态未知"
            ),
            "missing_fields": ["collected", "logistics_status", "signed", "freight_insurance"],
        }


def context_for(case):
    """Construct inputs from fixture and question only, never expected answers."""
    fixture = case.fixture
    goods = "900000001" if fixture == "inventory" else "900000002"
    question = case.question
    context = {
        "id": "eval-" + case.id,
        "message_id": "q1",
        "conversation_revision": 1,
        "shop_id": "synthetic-shop",
        "buyer_ref": "synthetic-buyer",
        "knowledge_version": VERSION,
        "knowledge": deepcopy(KNOWLEDGE),
        "history": [],
        "product_context": {
            "goods_id": goods,
            "title": next(p["goods_name"] for p in PRODUCTS if p["goods_id"] == goods),
            "source_message_id": "linked-card",
        },
        "message": {"id": "q1", "message_type": "text", "content": question},
        "pending_questions": [{"message_id": "q1", "message_type": "text", "content": question}],
    }
    if fixture == "linked":
        context["history"] = [
            {"id": "past", "direction": "outbound", "content": "刚才介绍法国纸币甲，库存21件。"}
        ]
    if fixture == "memory":
        context["history"] = [
            {"id": "past", "direction": "inbound", "content": "我要退款"},
            {
                "id": "answered",
                "direction": "outbound",
                "content": "退款诉求已由人工核实。本次改聊波兰纸币乙。",
            },
        ]
    if fixture == "generic_shipping":
        context["product_context"] = {}
    if fixture in {"cancelled", "unpaid", "logistics"}:
        context["order_reference"] = {"order_id": "synthetic-order"}
    if fixture == "batch":
        context["pending_questions"] = [
            {"message_id": "q1", "message_type": "text", "content": "这款现在有多少件库存？"},
            {"message_id": "q2", "message_type": "text", "content": question},
        ]
        context["message_id"] = "q2"
        context["message"] = {"id": "q2", "message_type": "text", "content": question}
        context["question_contexts"] = {
            "q1": context["product_context"],
            "q2": context["product_context"],
        }
    return context
