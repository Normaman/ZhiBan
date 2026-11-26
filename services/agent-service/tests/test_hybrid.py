from copilot.knowledge.hybrid import fuse, passages, search


def test_rrf_deduplicates_and_prefers_agreement():
    assert fuse(["exact", "both", "exact"], ["meaning", "both"]) == ["both", "exact", "meaning"]


def test_embedding_windows_include_tail_and_overlap():
    text = "甲" * 1000 + "末尾质保一年"
    result = passages({"title": "条款", "text": text})
    assert "末尾质保一年" in result[-1]
    assert all(len(piece) <= 403 for piece in result)


async def test_semantic_failure_is_visible_and_retains_keyword_fallback(monkeypatch):
    monkeypatch.setenv("RAG_ENABLED", "1")

    def failed():
        raise RuntimeError("private connection details")

    monkeypatch.setattr("copilot.knowledge.hybrid.backend", failed)
    entries = [{"source_id": "shipping", "text": "现货48小时发货"}]
    result, status = await search({}, entries, "什么时候发货")
    assert result == entries
    assert status == {"mode": "bm25_degraded", "reason": "RAG_UNAVAILABLE"}
