"""Verify local hybrid retrieval in a disposable PostgreSQL database using synthetic facts."""

import json
import os
import subprocess
import uuid
from pathlib import Path

from dev import ROOT, environment, postgres_binary


def admin(database, sql):
    r = subprocess.run(
        [
            postgres_binary("psql"),
            "-h",
            str(Path.cwd() / ".runtime"),
            "-p",
            "55439",
            "-d",
            database,
            "-v",
            "ON_ERROR_STOP=1",
        ],
        input=sql,
        text=True,
        capture_output=True,
    )
    if r.returncode:
        raise RuntimeError("ADMIN_DATABASE_OPERATION_FAILED")


def main():
    os.chdir(ROOT)
    v = environment()
    os.environ.update(v)
    os.environ["PSQL_BINARY"] = postgres_binary("psql")
    name = "copilot_rag_verify_" + uuid.uuid4().hex[:12]

    admin("postgres", "CREATE DATABASE " + name + " OWNER copilot")
    os.environ["RAG_DB_NAME"] = name
    try:
        from copilot.knowledge.hybrid import HybridIndex
        from copilot.knowledge.retrieval import rank

        admin(
            name,
            Path("services/agent-service/copilot/knowledge/vector_schema.sql").read_text()
            + "\nALTER TABLE rag_manifests OWNER TO copilot; ALTER TABLE rag_chunks OWNER TO copilot;",
        )
        index = HybridIndex()
        entries = [
            {
                "source_id": "ship",
                "source_type": "shop_policy",
                "topics": ["shipping"],
                "text": "订单完成支付后，两天内交给快递承运。",
            },
            {
                "source_id": "product",
                "source_type": "product",
                "goods_id": "one",
                "text": "保温杯容量600毫升。",
            },
            {
                "source_id": "other-product",
                "source_type": "product",
                "goods_id": "two",
                "text": "保温杯容量2000毫升。",
            },
        ]
        context = {"shop_id": "shop-a", "knowledge_version": "version-1", "knowledge": entries}
        query_text = "付款后多久能寄走？"
        results, trace = index.search(context, entries, query_text)
        assert results and results[0]["source_id"] == "ship", "SEMANTIC_RECALL_FAILED"
        assert rank(entries, query_text) == [], "LEXICAL_BASELINE_NOT_EMPTY"
        product, trace2 = index.search(context, entries[1:2], "容量多少？")
        assert product and all(e["source_id"] == "product" for e in product), "PRODUCT_SCOPE_FAILED"
        other = {
            "shop_id": "shop-b",
            "knowledge_version": "version-1",
            "knowledge": [dict(entries[0], text="支付订单后三天内交给快递承运。")],
        }
        found, _ = index.search(other, other["knowledge"], query_text)
        assert found and "三天" in found[0]["text"], "SHOP_SCOPE_FAILED"
        newer = {
            **context,
            "knowledge_version": "version-2",
            "knowledge": [dict(entries[0], text="支付订单后五天内交给快递承运。")],
        }
        found, _ = index.search(newer, newer["knowledge"], query_text)
        assert found and "五天" in found[0]["text"], "VERSION_SCOPE_FAILED"
        index.ready.clear()
        original = index.embedding.encode
        calls = []

        def counted(texts, *args, **kwargs):
            calls.append(len(texts))
            return original(texts, *args, **kwargs)

        index.embedding.encode = counted
        index.search(context, entries, query_text)
        assert calls == [1], "RESTART_REINDEXED_UNCHANGED_DOCUMENTS"
        result = {
            "semantic_without_keyword_match": True,
            "product_isolation": True,
            "shop_isolation": True,
            "version_isolation": True,
            "persisted_index_reused": True,
            "trace": trace,
            "storage": "pgvector",
            "models_local": True,
        }
        Path(".runtime/hybrid-real-result.json").write_text(json.dumps(result, indent=2))
        print(json.dumps(result))
    finally:
        admin("postgres", "DROP DATABASE " + name + " WITH (FORCE)")


if __name__ == "__main__":
    main()
