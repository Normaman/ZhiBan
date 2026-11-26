"""Read business facts through Core; keep the platform adapter out of Agent tools."""


class AgentBusinessClient:
    def __init__(self, core, shop_id):
        self.core = core
        self.shop_id = shop_id

    async def products(self):
        response = await self.core.post("/internal/v1/agent-tools/products", timeout=70)
        response.raise_for_status()
        result = response.json()
        if result.get("shop_id") != self.shop_id or not isinstance(result.get("products"), list):
            raise RuntimeError("PLATFORM_PRODUCT_SCOPE_MISMATCH")
        return result["products"]

    async def product_detail(self, goods_id):
        response = await self.core.post(
            "/internal/v1/agent-tools/product-detail",
            json={"goods_id": goods_id},
            timeout=70,
        )
        response.raise_for_status()
        result = response.json()
        if result.get("shop_id") != self.shop_id or not isinstance(result.get("detail"), dict):
            raise RuntimeError("PLATFORM_PRODUCT_SCOPE_MISMATCH")
        return result["detail"]

    async def order_detail(self, order_id, buyer_ref):
        response = await self.core.post(
            "/internal/v1/agent-tools/order-detail",
            json={"order_id": order_id, "buyer_ref": buyer_ref},
            timeout=70,
        )
        response.raise_for_status()
        result = response.json()
        if result.get("shop_id") != self.shop_id or not isinstance(result.get("order"), dict):
            raise RuntimeError("PLATFORM_ORDER_SCOPE_MISMATCH")
        return result["order"]
