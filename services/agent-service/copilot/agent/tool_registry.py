"""Single source of truth for model-visible tool contracts and access metadata."""

from dataclasses import dataclass
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class Parameters(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class ProductQuery(Parameters):
    query: str = Field(
        default="",
        max_length=200,
        description="按目录查商品时填当前买家问题中出现的名称、国家、品牌或品类词，不填价格、库存等问法词。多个独立条件用空格分开，例如‘比利时纸币’写‘比利时 纸币’；纸币、硬币等不同类别不可互换。若查询成功但0命中，可根据结果放宽条件：去掉过窄的修饰词，保留有区分度且仍来自买家原话的词，再查一次；不要补造买家没提到的商品词。",
    )
    goods_id: str | None = Field(default=None, max_length=200)
    scope: Literal["current", "catalog"] = Field(
        default="current",
        description="current 查询本次咨询商品；按名称、国家、品牌或品类搜索其他商品时使用 catalog。",
    )
    comparison: Literal["none", "lowest_in_stock_price"] = Field(
        default="none",
        description="普通目录查询用 none；用户要求比较多款商品中哪款最低价时用 lowest_in_stock_price。",
    )
    price_basis: Literal["group", "single"] = Field(
        default="group", description="最低价比较口径：group 普通拼单价；single 单买价。"
    )


class PolicyQuery(Parameters):
    query: str = Field(default="", max_length=200)
    topic: str = Field(pattern="^(shipping|policy|faq)$")


class SkuQuery(Parameters):
    goods_id: str | None = Field(default=None, min_length=1, max_length=200)
    spec_query: str = Field(
        default="",
        max_length=200,
        description="只填颜色、尺寸、型号等 SKU 规格词，不填‘最便宜’‘库存’。留空比较该商品全部规格。每包/每份包含几件属于商品属性，不是可售库存；不要把包装数量问题改成 SKU 库存查询。",
    )
    price_basis: Literal["group", "single"] = Field(
        default="group", description="group 普通拼单价；single 单买价。未包含优惠券、活动和运费。"
    )
    in_stock_only: bool = Field(
        default=True, description="默认只比较已上架且明确有库存的规格；查缺货规格时可设 false。"
    )


class ShippingQuery(Parameters):
    goods_id: str | None = Field(default=None, min_length=1, max_length=200)
    province: str = Field(
        default="",
        max_length=30,
        description="只填省/自治区/直辖市名称，不要填买家姓名、电话或详细地址。",
    )
    spec_query: str = Field(
        default="",
        max_length=200,
        description="仅在问题涉及具体规格发货时间时填写颜色、型号等规格名称。",
    )


class Handoff(Parameters):
    reason: str = Field(min_length=1, max_length=200)


@dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str
    param_model: type[BaseModel]
    handler: str
    intents: frozenset[str] = frozenset()
    timeout_seconds: int = 8
    failure_code: str | None = None
    requires_order_context: bool = False
    catalog_access: bool = False
    side_effect: bool = False

    def schema(self):
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.param_model.model_json_schema(),
            },
        }


TOOL_SPECS = (
    ToolSpec(
        "get_order_details",
        "只读查询本会话已绑定且核验买家归属的订单。返回平台实际取得的订单状态、下单时间、商品、规格和购买数量，并列出未取得字段。用于核对当前状态与履约前提；已知状态可以解释，缺失的发货、物流、签收或运费险不能推断。不能执行订单或售后操作。",
        Parameters,
        "order_details",
        frozenset({"logistics"}),
        timeout_seconds=55,
        requires_order_context=True,
    ),
    ToolSpec(
        "get_product_shipping",
        "实时查询当前商品的发货时效、商品/规格预售及运费模板。只用于多久发货、预售、包邮、某省邮费或不配送地区；不提供发货地/仓库地址。province 只传省级地区，不传详细地址；运费返回计费规则，不能冒充本订单最终运费。",
        ShippingQuery,
        "shipping",
        frozenset({"shipping"}),
        timeout_seconds=55,
        failure_code="product_shipping_unavailable",
    ),
    ToolSpec(
        "get_product_skus",
        "实时查询一个已确定商品的 SKU 规格、可售库存、普通拼单价和单买价，并计算该商品内最低价。用于查询现有多少可售库存、指定颜色/型号的价格或库存、该商品哪个规格最便宜。每包/每份包含数量属于商品属性，应查商品知识，不是库存。当前商品已确定时使用其 goods_id；查询失败且商品 ID 未变时，不要改用目录搜索来代替 SKU 事实，目录价格不能证明 SKU 价格或库存。跨多个目录商品比较最低价时，使用 get_shop_products 的最低价比较模式。默认只比较已上架且明确有库存的规格；不含活动、优惠券或运费。",
        SkuQuery,
        "skus",
        frozenset({"inventory", "product", "price"}),
        failure_code="sku_query_failed",
    ),
    ToolSpec(
        "get_shop_products",
        "查询当前店铺在售商品目录、商品级价格和库存。当前选中/刚刚更正的商品已确定时，按其 goods_id 查询当前商品；只有买家要求另找商品、按名称或品类筛选其他商品时才用 scope=catalog。按目录查找时，query 填当前问题中出现的名称、国家、品牌或品类词；多个独立条件用空格分开，例如‘比利时纸币’写‘比利时 纸币’，纸币、硬币等类别不能互换。若成功查询但0命中，检查工具返回的 query_keywords 和 matched_count；可去掉过窄词后重试一次，查询词必须来自当前买家问题。仍无命中时只说明本次目录未找到，不推断全店没有。普通查找或有无查询用 comparison=none；用户比较多款最低价时用 lowest_in_stock_price，系统核对匹配商品的 SKU 价格和库存，不用列表价推断。只有明确问单买价时 price_basis=single；默认 group 为普通拼单价，不含活动、优惠券和运费。",
        ProductQuery,
        "products",
        frozenset({"inventory", "price"}),
        failure_code="product_query_failed",
        catalog_access=True,
        timeout_seconds=55,
    ),
    ToolSpec(
        "get_product_knowledge",
        "查询当前已确定商品的平台参数、规格名称及当前知识版本中的商品扩展资料，适合查询每包/每份包含数量等商品属性。当前商品已绑定时传真实 goods_id 并使用 current；不要用目录搜索替代当前商品的知识查询。实时可售库存和价格必须使用实时商品工具，不能从描述性资料推断。",
        ProductQuery,
        "product_knowledge",
        frozenset({"inventory", "product"}),
        timeout_seconds=55,
    ),
    ToolSpec(
        "search_customer_service_knowledge",
        "检索当前任务指定版本的店铺发货(shipping)、售后(policy)或常见问答(faq，如正品、保真、发票)。query 填本次问题或关键词，必须核对命中资料的适用范围；回答规范不属于事实证据。",
        PolicyQuery,
        "policies",
        frozenset({"product", "shipping", "shipping_origin", "logistics", "after_sales_policy"}),
        timeout_seconds=55,
    ),
    ToolSpec(
        "transfer_conversation",
        "建议转人工并停止生成回复；仅标记本次草稿，实际接管由人工操作，不调用平台转接。",
        Handoff,
        "handoff",
        side_effect=True,
    ),
)

TOOL_SPECS_BY_NAME = {spec.name: spec for spec in TOOL_SPECS}
