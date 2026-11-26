import pytest
from copilot.knowledge.documents import parse_document
from copilot.knowledge.organize import organize, semantic_suggestions
from test_documents import request


def preview(text):
    return organize(parse_document(request("店铺规则.txt", text.encode())))


def test_mixed_rules_split_by_topic_without_rewriting():
    text = "现货48小时内发货，偏远地区除外。七天内可退货，退货运费由买家承担。杯子容量500毫升，材质为不锈钢。适合通勤使用，推荐轻量款。"
    result = preview(text)
    assert [c["suggested_topic"] for c in result["chunks"]] == [
        "shipping",
        "policy",
        "spec",
        "recommendation",
    ]
    assert "".join(c["text"] for c in result["chunks"]) == text
    assert all(c["document_sha256"] and c["source_location"] for c in result["chunks"])
    assert len({c["source_id"] for c in result["chunks"]}) == 4


def test_dependent_exception_stays_with_its_condition_and_requires_review():
    result = preview("现货48小时内发货；但申请退货时需先联系人工。")
    assert len(result["chunks"]) == 1
    assert not result["chunks"][0]["suggested_topic"]
    assert result["chunks"][0]["classification_mixed"] is True
    assert "但申请退货" in result["chunks"][0]["text"]


def test_heading_context_is_retained_and_heading_not_auto_published():
    result = preview("售后规则\n\n非质量问题退货运费由买家承担。\n\n商品参数\n\n杯子容量500毫升。")
    entries = result["chunks"]
    assert entries[0]["is_heading"] is True
    assert entries[1]["text"].startswith("售后规则\n")
    assert entries[1]["suggested_topic"] == "policy"
    assert entries[2]["is_heading"] is True
    assert any("杯子容量500毫升" in e["text"] and e["suggested_topic"] == "spec" for e in entries)


def test_comma_separated_delivery_and_return_rules_are_separate():
    result = preview("现货48小时发货，支持七天无理由退货。")
    assert [c["suggested_topic"] for c in result["chunks"]] == ["shipping", "policy"]


def test_unknown_text_is_not_guessed_and_semantic_is_optional(monkeypatch):
    monkeypatch.setenv("RAG_ENABLED", "0")
    result = preview("关于这个情况我们还没有确定处理方式。")
    assert result["chunks"][0]["suggested_topic"] == ""
    assert semantic_suggestions(result)["organization"]["unclassified"] == 1


def test_return_freight_is_after_sales_and_ids_are_repeatable():
    a = preview("退货运费由买家承担。")
    b = preview("退货运费由买家承担。")
    assert a == b
    assert a["chunks"][0]["suggested_topic"] == "policy"


def test_excessive_split_rejects_whole_document():
    text = "现货48小时内发货。七天无理由退货。" * 260
    with pytest.raises(ValueError, match="DOCUMENT_TOO_COMPLEX"):
        preview(text)


def test_mixed_topics_without_a_safe_boundary_require_manual_review():
    result = preview("现货48小时发货并支持七天无理由退货")
    assert len(result["chunks"]) == 1
    assert result["chunks"][0]["suggested_topic"] == ""
    assert result["chunks"][0]["classification_mixed"] is True


def test_local_semantic_suggestions_keep_ambiguous_items_unclassified(monkeypatch):
    import threading
    from types import SimpleNamespace

    np = pytest.importorskip("numpy")

    class Encoder:
        def encode(self, texts, **kwargs):
            return np.array(
                [
                    [1, 0, 0, 0, 0, 0],
                    [0, 1, 0, 0, 0, 0],
                    [0, 0, 1, 0, 0, 0],
                    [0, 0, 0, 1, 0, 0],
                    [0, 0, 0, 0, 1, 0],
                    [0, 0, 0, 0, 0, 1],
                    [0, 0, 1, 0, 0, 0],
                    [0.4, 0.4, 0.4, 0.4, 0.4, 0.4],
                ],
                dtype=float,
            )

    monkeypatch.setenv("RAG_ENABLED", "1")
    monkeypatch.setattr(
        "copilot.knowledge.hybrid.backend",
        lambda: SimpleNamespace(lock=threading.Lock(), embedding=Encoder()),
    )
    data = preview("刚收到东西，用了两次就不能用了。\n\n这件事情需要进一步明确。")
    before = [entry["text"] for entry in data["chunks"]]
    result = semantic_suggestions(data)
    assert result["chunks"][0]["suggested_topic"] == "policy"
    assert result["chunks"][0]["classification_method"] == "semantic"
    assert result["chunks"][1]["suggested_topic"] == ""
    assert [entry["text"] for entry in result["chunks"]] == before


def test_response_guidelines_are_separate_from_delivery_facts():
    result = preview(
        "客服回答规范\n\n称呼统一使用您好，不得承诺未经核实的发货时间。\n\n现货48小时发货。"
    )
    assert result["chunks"][0]["is_heading"]
    assert result["chunks"][1]["suggested_topic"] == "response_guidelines"
    assert result["chunks"][2]["suggested_topic"] == "shipping"


def test_faq_question_and_answer_stay_together_separate_from_style_rules():
    result = preview("回复保持简洁。\n\n问：保真吗？\n答：请先核实适用商品的授权材料。")
    entries = result["chunks"]
    assert [e["suggested_topic"] for e in entries] == ["response_guidelines", "faq"]
    assert "保真吗？" in entries[1]["text"]
    assert "请先核实适用商品的授权材料" in entries[1]["text"]
