"""In-memory business API responses for isolated Agent tests."""

from datetime import datetime, timezone


def now():
    return datetime.now(timezone.utc).isoformat()


class FakeBusinessClient:
    def __init__(self, settings=None):
        self.settings = settings

    async def products(self):
        return [
            {
                "goods_id": "test-goods-1",
                "goods_name": "测试保温杯",
                "price": "89.00",
                "quantity": 32,
                "fetched_at": now(),
                "mode": "test",
            }
        ]

    async def product_detail(self, goods_id):
        if goods_id != "test-goods-1":
            raise ValueError("PRODUCT_NOT_FOUND")
        return {
            "detail_schema_version": 3,
            "shipping": {"mode": "regular", "summary": "非预售；48小时内发货及揽收"},
            "freight": {"status": "unavailable", "free_regions": [], "paid_rules": []},
            "goods_id": goods_id,
            "goods_name": "测试保温杯",
            "category_path": ["测试分类", "杯具"],
            "description": "测试夹具：500mL 保温杯。",
            "skus": [
                {
                    "sku_id": "test-white",
                    "group_price_cents": 8900,
                    "single_price_cents": 9900,
                    "on_sale": True,
                    "specs": [{"name": "颜色", "value": "白色"}],
                    "quantity": 20,
                    "thumb_url": None,
                },
                {
                    "sku_id": "test-black",
                    "group_price_cents": 9900,
                    "single_price_cents": 10900,
                    "on_sale": True,
                    "specs": [{"name": "颜色", "value": "黑色"}],
                    "quantity": 12,
                    "thumb_url": None,
                },
            ],
            "skus_truncated": False,
            "attributes": [{"name": "容量", "values": ["500mL"]}],
            "attributes_status": "synced",
            "fetched_at": now(),
        }

    async def order_detail(self, order_id, buyer_ref):
        return {
            "order_id": order_id,
            "buyer_ref": buyer_ref,
            "order_status": "unknown",
            "status_text": None,
            "missing_fields": ["order_status", "logistics_status"],
            "fetched_at": now(),
        }


class FakeExecutionAuthority:
    """Core execution protocol fixture shared by independent Python runtimes."""

    def __init__(self):
        self.executions = {}

    async def post(self, path, *, json, **kwargs):
        import httpx

        run = path.split("/")[-3]
        action = path.rsplit("/", 1)[-1]
        entry = self.executions.get(run)
        status, payload = 200, {}
        if action == "claim":
            if entry and entry["request"] != json:
                status, payload = 409, {"detail": "RUN_PAYLOAD_CONFLICT"}
            elif entry and "result" in entry:
                payload = {"state": "completed", "result": entry["result"]}
            elif entry and entry.get("lease_id"):
                payload = {"state": "running"}
            else:
                entry = {"request": json, "lease_id": "lease-" + run}
                self.executions[run] = entry
                payload = {"state": "claimed", "lease_id": entry["lease_id"]}
        elif action == "complete":
            assert entry and json["lease_id"] == entry["lease_id"]
            entry["result"] = json["result"]
        elif action == "release":
            if entry and "result" not in entry:
                entry["lease_id"] = None
        else:
            raise AssertionError(path)
        return httpx.Response(
            status, json=payload, request=httpx.Request("POST", "http://core" + path)
        )
