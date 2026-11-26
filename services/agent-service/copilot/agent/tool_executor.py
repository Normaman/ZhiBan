"""Run-scoped tool registry and bounded executor adapted from upstream tools.

The model supplies business search parameters only. Shop, buyer and knowledge
version come from the trusted run context. There are no platform write tools.
"""

import asyncio
import json
import time

from pydantic import ValidationError

from ..business_errors import BusinessToolError
from .media import image_url
from .queries import knowledge, orders, products as product_queries
from .queries import shipping as shipping_queries
from .queries import sku as sku_queries
from .tool_registry import TOOL_SPECS_BY_NAME
from .tool_registry import ProductQuery as ProductQuery


class ToolExecutor:
    def __init__(self, context, connector, concurrency=3):
        self.context = context
        self.connector = connector
        self.registry = dict(TOOL_SPECS_BY_NAME)
        if not context.get("order_context", {}).get("fetched_at") and not (
            context.get("order_reference", {}).get("order_id") and context.get("buyer_ref")
        ):
            self.registry = {
                name: spec
                for name, spec in self.registry.items()
                if not spec.requires_order_context
            }
        # Intent is a revisable hypothesis. Authorization comes from the trusted
        # shop/buyer context; it must not disappear after a wrong classification.
        self.results = {}
        self.blocked_error = None
        self.evidence = {}
        self.inventory_evidence = {}
        self.catalog_inventory_evidence = set()
        self.catalog_products = None
        self.product_details = {}
        self.card_options = {}
        self.trace = []
        self.needs_human = False
        self.slots = asyncio.Semaphore(concurrency)
        self.missing = set()

    def schemas(self):
        return [entry.schema() for entry in self.registry.values()]

    def cite(self, evidence):
        # Keep namespaces and versions distinct even when native source IDs coincide.
        identity = (evidence["source_type"], evidence["source_id"], evidence["source_version"])
        for key, known in self.evidence.items():
            if identity == (known["source_type"], known["source_id"], known["source_version"]):
                return {"evidence_id": key, **known}
        key = f"e{len(self.evidence) + 1}"
        self.evidence[key] = evidence
        return {"evidence_id": key, **evidence}

    async def skus(self, params):
        result = await sku_queries.lookup_skus(self.connector, self.context, params, self.cite)
        for key in result.pop("inventory_evidence_ids", []):
            self.inventory_evidence[key] = result["goods_id"]
        self.missing.discard("sku_query_failed")
        self.missing.discard("sku_details_incomplete")
        self.missing.discard("sku_not_found")
        if result.get("missing"):
            self.missing.add(result["missing"])
        return result

    async def shipping(self, params):
        result = await shipping_queries.lookup_shipping(self.connector, self.context, params, self.cite)
        self.missing.discard("product_shipping_unavailable")
        if result.get("missing"):
            self.missing.add(result["missing"])
        return result

    async def products(self, params):
        return await product_queries.products(self, params)

    async def catalog_price_comparison(self, params, products, keywords):
        return await product_queries.catalog_price_comparison(self, params, products, keywords)

    def remember_cards(self, products, evidence):
        by_id = {str(p["goods_id"]): p for p in products}
        options = []
        for entry in evidence:
            p = by_id.get(entry["source_id"])
            if (
                not p
                or type(p.get("quantity")) is not int
                or p["quantity"] <= 0
                or not p.get("goods_name")
                or str(p.get("goods_type", "")) == "20"
            ):
                continue
            card = {
                "goods_id": str(p["goods_id"]),
                "title": p["goods_name"][:1000],
                "image_url": image_url(p.get("thumb_url")),
                "price": str(p["price"]) if p.get("price") is not None else None,
                "fetched_at": p["fetched_at"],
            }
            self.card_options[card["goods_id"]] = {
                "card": card,
                "evidence_id": entry["evidence_id"],
            }
            options.append(card)
        return options

    def resolve_cards(self, answer):
        if len(set(answer.product_card_ids)) != len(answer.product_card_ids) or any(
            key not in self.card_options for key in answer.product_card_ids
        ):
            raise ValueError("INVALID_PRODUCT_CARDS")
        for key in answer.product_card_ids:
            evidence_id = self.card_options[key]["evidence_id"]
            if evidence_id not in answer.evidence_ids:
                answer.evidence_ids.append(evidence_id)
        return [self.card_options[key]["card"] for key in answer.product_card_ids]

    def has_inventory_evidence(self, keys):
        if self.context.get("understanding", {}).get("product_scope") == "catalog":
            return any(key in self.catalog_inventory_evidence for key in keys)
        goods = self.context.get("product_context", {}).get("goods_id")
        return any(
            key in self.inventory_evidence and (not goods or self.inventory_evidence[key] == goods)
            for key in keys
        )

    async def knowledge(self, source_type, params):
        return await knowledge.knowledge(self, source_type, params)

    async def product_knowledge(self, params):
        return await knowledge.product_knowledge(self, params)

    async def policies(self, params):
        return await knowledge.policies(self, params)

    async def handoff(self, params):
        self.needs_human = True
        return {"outcome": "needs_human", "platform_transfer_executed": False}

    async def order_details(self, params):
        return await orders.order_details(self, params)

    async def execute(self, call):
        started = time.monotonic()
        status = "ok"
        action = None
        key = None
        cache_result = True
        entry = self.registry.get(call.function.name)
        try:
            if entry is None:
                status = "unknown_tool"
                result = {"error": "TOOL_NOT_ALLOWED"}
            else:
                params = entry.param_model.model_validate_json(call.function.arguments)
                key = json.dumps(
                    [
                        entry.name,
                        params.model_dump(),
                        self.context.get("product_context", {}),
                        self.context.get("understanding", {}),
                    ],
                    sort_keys=True,
                    ensure_ascii=False,
                )
                if self.blocked_error:
                    return {
                        "role": "tool",
                        "tool_call_id": call.id,
                        "content": json.dumps({"error": self.blocked_error, "retryable": False}),
                    }
                if key in self.results:
                    self.trace.append({"name": entry.name, "status": "reused", "duration_ms": 0})
                    return {
                        "role": "tool",
                        "tool_call_id": call.id,
                        "content": json.dumps(
                            {**self.results[key], "reused": True}, ensure_ascii=False
                        ),
                    }
                if (
                    (
                        self.context.get("product_context", {}).get("ambiguous")
                        or self.context.get("understanding", {}).get("product_ambiguous")
                    )
                    and entry.handler in {"skus", "shipping", "product_knowledge", "products"}
                    and not (entry.handler == "products" and params.scope == "catalog")
                ):
                    self.missing.add("product_selection")
                    self.trace.append({"name": entry.name, "status": "blocked", "duration_ms": 0})
                    return {
                        "role": "tool",
                        "tool_call_id": call.id,
                        "content": json.dumps(
                            {"error": "PRODUCT_CONTEXT_AMBIGUOUS", "evidence": []}
                        ),
                    }
                if entry.handler == "products" and params.scope == "catalog":
                    action = (
                        "catalog_price_comparison"
                        if params.comparison == "lowest_in_stock_price"
                        else "catalog_search"
                    )
                async with self.slots:
                    result = await asyncio.wait_for(
                        getattr(self, entry.handler)(params),
                        timeout=entry.timeout_seconds,
                    )
                self.results[key] = result
        except asyncio.TimeoutError:
            status = "timeout"
            cache_result = False
            result = {"error": "TOOL_TIMEOUT", "retryable": True}
        except ValidationError:
            status = "invalid_arguments"
            result = {"error": "INVALID_TOOL_ARGUMENTS"}
        except BusinessToolError as error:
            status = "failed"
            allowed = {
                "PRODUCT_QUERY_REJECTED_3000000",
                "PRODUCT_QUERY_FAILED",
                "PRODUCT_SESSION_INVALID",
                "PRODUCT_SESSION_REFRESH_FAILED",
                "UNRECOGNIZED_PRODUCT_LIST_SCHEMA",
                "PLATFORM_SESSION_EXPIRED",
                "ORDER_BUYER_MISMATCH",
                "ORDER_DETAIL_SCOPE_MISMATCH",
                "PRODUCT_DETAIL_SCOPE_MISMATCH",
                "SKU_DETAILS_UNAVAILABLE",
            }
            result = {"error": str(error) if str(error) in allowed else "TOOL_EXECUTION_FAILED"}
            if str(error) == "SKU_DETAILS_UNAVAILABLE" and entry and entry.handler == "skus":
                result["retryable"] = False
            if str(error) == "PLATFORM_SESSION_EXPIRED":
                self.missing.add("platform_session_expired")
                self.blocked_error = "PLATFORM_SESSION_EXPIRED"
            if str(error) in {
                "ORDER_BUYER_MISMATCH",
                "ORDER_DETAIL_SCOPE_MISMATCH",
                "PRODUCT_DETAIL_SCOPE_MISMATCH",
            }:
                self.blocked_error = str(error)
            if entry and entry.failure_code:
                self.missing.add(entry.failure_code)
        except Exception:
            status = "failed"
            result = {"error": "TOOL_EXECUTION_FAILED"}
            if entry and entry.failure_code:
                self.missing.add(entry.failure_code)
        if key is not None and cache_result:
            self.results[key] = result
        self.trace.append(
            {
                "name": entry.name if entry else "unknown_tool",
                "status": status,
                "duration_ms": int((time.monotonic() - started) * 1000),
                **({"action": action} if action else {}),
                **({"retrieval": result["retrieval"]} if "retrieval" in result else {}),
            }
        )
        return {
            "role": "tool",
            "tool_call_id": call.id,
            "content": json.dumps(result, ensure_ascii=False),
        }

    def missing_message(self):
        messages = {
            "platform_session_expired": "拼多多登录已失效，请重新登录后查询实时资料，或由人工核实。",
            "faq_missing": "店铺尚未发布适用的问答，请补充已核实的答案或转人工。",
            "sku_query_failed": "规格实时查询失败，请稍后重试或人工核实，不能用缓存库存或商品总价代替。",
            "sku_details_incomplete": "平台返回的规格价格、库存或上架信息不完整，暂不能确定最低价，请人工核实。",
            "sku_not_found": "未找到匹配规格，请核对颜色、型号或尺寸。",
            "catalog_price_scope_too_large": "匹配商品较多，暂不能完整比较最低价；请补充品牌、款式或其他名称特征缩小范围。",
            "catalog_price_query_incomplete": "部分匹配商品的规格价格、库存或上架信息未取得，暂不能确定最低价，请人工核实。",
            "catalog_price_not_available": "匹配商品中没有已上架且明确有库存的规格，暂时无法确认可售最低价。",
            "product_query_failed": "商品接口本次查询失败，请稍后重试或人工核实，不需要补写库存知识。",
            "product_selection": "还不能确定您咨询的是哪件商品，请发送商品链接或名称；查询某个颜色、尺码时还需提供具体规格。",
            "product_not_found": "未找到对应商品，请核对商品链接或名称后重新查询。",
            "inventory_unavailable": "平台本次未返回该商品库存，需要人工核实，不能按零库存或有货处理。",
            "product_specs_missing": "当前商品缺少经过核实的规格资料，请在知识库中为该商品补充参数或使用说明。",
            "product_shipping_unavailable": "当前商品发货或运费设置未取得，请人工核实；不能按全店包邮或统一发货时间处理。",
            "shipping_policy_missing": "店铺尚未发布适用的发货规则，请补充发货时效及例外条件。",
            "shipping_origin_missing": "知识库没有可核实的发货地点，请人工确认；不能按商品产地或店铺登记地址推断。",
            "after_sales_policy_missing": "店铺尚未发布适用的售后规则，请补充经过确认的售后政策。",
        }
        return next(
            (message for code, message in messages.items() if code in self.missing),
            "当前依据不足，请补充商品信息或转人工核实。",
        )

    async def execute_parallel(self, calls):
        # As upstream: explicitly registered reads may run concurrently; local
        # handoff operations and unknown tools are processed in request order.
        results = []
        batch = []
        for call in calls:
            entry = self.registry.get(call.function.name)
            if entry is None or entry.side_effect:
                if batch:
                    results.extend(await asyncio.gather(*(self.execute(c) for c in batch)))
                    batch = []
                results.append(await self.execute(call))
            else:
                batch.append(call)
        if batch:
            results.extend(await asyncio.gather(*(self.execute(c) for c in batch)))
        return results
