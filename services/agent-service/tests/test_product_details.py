from copilot.agent.tool_executor import ProductQuery, ToolExecutor


async def test_synced_metadata_supplies_static_agent_facts_but_never_cached_inventory():
    context = {
        "knowledge": [],
        "product_context": {"goods_id": "goods-a"},
        "platform_product_facts": {
            "goods_id": "goods-a",
            "goods_name": "测试保温杯",
            "category_path": ["杯具"],
            "attributes": [{"name": "容量", "values": ["500mL"]}],
            "specs": [[{"name": "颜色", "value": "白色"}]],
            "quantity": 9999,
            "price": "987.65",
            "fetched_at": "2026-10-27T10:00:00Z",
        },
    }
    tools = ToolExecutor(context, object())
    result = await tools.product_knowledge(ProductQuery())
    assert len(result["evidence"]) == 1
    text = result["evidence"][0]["evidence_text"]
    assert "500mL" in text and "颜色：白色" in text
    assert "9999" not in text and "987.65" not in text
    assert "product_specs_missing" not in tools.missing
    foreign = await tools.product_knowledge(ProductQuery(goods_id="goods-b"))
    assert foreign["evidence"] == []


async def test_exact_product_description_and_bound_knowledge_do_not_require_verbatim_question(
    monkeypatch,
):
    monkeypatch.setenv("RAG_ENABLED", "0")
    context = {
        "message": {"content": "一份有几张？"},
        "product_context": {"goods_id": "goods-a"},
        "knowledge_version": "confirmed-version",
        "knowledge": [
            {
                "source_type": "product",
                "source_id": "quantity-a",
                "goods_id": "goods-a",
                "topics": ["spec"],
                "text": "销售单位：每件一张。",
            },
            {
                "source_type": "product",
                "source_id": "quantity-b",
                "goods_id": "goods-b",
                "topics": ["spec"],
                "text": "每份十张。",
            },
        ],
        "platform_product_facts": {
            "goods_id": "goods-a",
            "goods_name": "甲商品",
            "description": "每份一张，按单张出售。",
            "attributes": [],
            "specs": [],
            "quantity": 99,
        },
    }
    tool = ToolExecutor(context, None)
    result = await tool.product_knowledge(ProductQuery(query="一份有几张"))
    assert any(e["source_id"] == "quantity-a" for e in result["evidence"])
    assert any("按单张出售" in e["evidence_text"] for e in result["evidence"])
    assert not any(
        "十张" in e["evidence_text"] or "99" in e["evidence_text"] for e in result["evidence"]
    )
