"""Local embeddings + scoped pgvector search + BM25/RRF + local cross-encoder.

Only derived vectors live here. Spring's immutable knowledge snapshots remain authoritative.
"""

import asyncio
import hashlib
import json
import os
import threading
from pathlib import Path

from .retrieval import rank
from .model_catalog import MODELS


def passages(entry):
    text = entry["text"]
    title = str(entry.get("title", ""))[:60]
    return [f"{title}\n{text[start : start + 400]}" for start in range(0, len(text), 340)]


def fuse(keyword, vector, limit=20):
    scores = {}
    for ranking in (keyword, vector):
        for index, key in enumerate(dict.fromkeys(ranking), 1):
            scores[key] = scores.get(key, 0) + 1 / (60 + index)
    return sorted(scores, key=lambda key: (-scores[key], key))[:limit]


class HybridIndex:
    def __init__(self):
        import torch
        from huggingface_hub import snapshot_download
        from sentence_transformers import CrossEncoder, SentenceTransformer

        torch.set_num_threads(2)
        device = os.getenv("RAG_DEVICE", "cuda" if torch.cuda.is_available() else "cpu")
        embedding_path = snapshot_download("BAAI/bge-small-zh-v1.5",
            revision=MODELS["BAAI/bge-small-zh-v1.5"], local_files_only=True)
        reranker_path = snapshot_download("BAAI/bge-reranker-base",
            revision=MODELS["BAAI/bge-reranker-base"], local_files_only=True)
        self.revision = f"bge-small-zh:{Path(embedding_path).name}:segments-v1"
        self.embedding = SentenceTransformer(
            embedding_path, device=device, local_files_only=True, trust_remote_code=False
        )
        self.reranker = CrossEncoder(
            reranker_path,
            device=device,
            local_files_only=True,
            trust_remote_code=False,
            max_length=512,
        )
        self.lock = threading.Lock()
        self.ready = set()
        if self.embedding.get_embedding_dimension() != 512:
            raise ValueError("RAG_DIMENSION_MISMATCH")

    @staticmethod
    def connection():
        import psycopg

        return psycopg.connect(
            host=os.getenv("RAG_DB_HOST", "127.0.0.1"),
            port=int(os.getenv("RAG_DB_PORT", "55439")),
            dbname=os.environ["RAG_DB_NAME"],
            user=os.getenv("RAG_DB_USER", "copilot"),
            password=os.environ["RAG_DB_PASSWORD"],
            connect_timeout=3,
            options="-c statement_timeout=15000",
        )

    def ensure(self, context):
        shop, version = context.get("shop_id"), context.get("knowledge_version")
        entries = context.get("knowledge_index", context.get("knowledge", []))
        if not shop or not version or not entries:
            return
        fingerprint = hashlib.sha256(
            json.dumps(entries, sort_keys=True, ensure_ascii=False).encode()
        ).hexdigest()
        key = (shop, version, self.revision)
        cache_key = (*key, fingerprint)
        if cache_key in self.ready:
            return
        with self.connection() as db:
            old = db.execute(
                "SELECT fingerprint FROM rag_manifests WHERE shop_id=%s AND knowledge_version=%s AND model_revision=%s",
                key,
            ).fetchone()
            if old and old[0] != fingerprint:
                raise ValueError("RAG_VERSION_CONFLICT")
            if not old:
                segments = [
                    (e["source_id"], i, text) for e in entries for i, text in enumerate(passages(e))
                ]
                if len(segments) > 15000:
                    raise ValueError("RAG_INDEX_TOO_LARGE")
                vectors = self.embedding.encode(
                    [s[2] for s in segments],
                    batch_size=32,
                    normalize_embeddings=True,
                    show_progress_bar=False,
                )
                # Concurrent processes serialize the final replacement; readers see only committed versions.
                db.execute(
                    "SELECT pg_advisory_xact_lock(hashtextextended(%s,0))", (json.dumps(key),)
                )
                old = db.execute(
                    "SELECT fingerprint FROM rag_manifests WHERE shop_id=%s AND knowledge_version=%s AND model_revision=%s",
                    key,
                ).fetchone()
                if old and old[0] != fingerprint:
                    raise ValueError("RAG_VERSION_CONFLICT")
                if not old:
                    with db.cursor() as cursor:
                        cursor.executemany(
                            "INSERT INTO rag_chunks(shop_id,knowledge_version,model_revision,source_id,part,embedding) VALUES (%s,%s,%s,%s,%s,%s::vector)",
                            [
                                (*key, source, i, json.dumps(v.tolist()))
                                for (source, i, _), v in zip(segments, vectors)
                            ],
                        )
                    db.execute(
                        "INSERT INTO rag_manifests(shop_id,knowledge_version,model_revision,fingerprint) VALUES (%s,%s,%s,%s)",
                        (*key, fingerprint),
                    )
        if len(self.ready) > 128:
            self.ready.clear()
        self.ready.add(cache_key)

    def search(self, context, entries, query, limit=5):
        if not entries or not query.strip():
            return rank(entries, query, limit), {
                "mode": "hybrid",
                "vector_candidates": 0,
                "keyword_candidates": 0,
                "reranked": 0,
            }
        with self.lock:
            self.ensure(context)
            ids = [e["source_id"] for e in entries]
            by_id = {e["source_id"]: e for e in entries}
            keyword = rank(entries, query, 20)
            query_vector = self.embedding.encode(
                ["为这个句子生成表示以用于检索相关文章：" + query[:1000]],
                normalize_embeddings=True,
                show_progress_bar=False,
            )[0]
            with self.connection() as db:
                rows = db.execute(
                    "SELECT source_id,part FROM rag_chunks WHERE shop_id=%s AND knowledge_version=%s AND model_revision=%s AND source_id=ANY(%s) ORDER BY embedding <=> %s::vector LIMIT 40",
                    (
                        context["shop_id"],
                        context["knowledge_version"],
                        self.revision,
                        ids,
                        json.dumps(query_vector.tolist()),
                    ),
                ).fetchall()
            vector = list(dict.fromkeys(row[0] for row in rows))[:20]
            selected = fuse([e["source_id"] for e in keyword], vector)
            best_parts = {}
            for source, part in rows:
                best_parts.setdefault(source, part)
            # Re-rank the matched passage, preserving the original full source for citations.
            pairs = []
            for source in selected:
                parts = passages(by_id[source])
                if source in best_parts:
                    passage = parts[best_parts[source]]
                else:
                    ranked = rank([{"text": text} for text in parts], query, 1)
                    passage = ranked[0]["text"] if ranked else parts[0]
                pairs.append((query[:1000], passage))
            scores = (
                self.reranker.predict(pairs, batch_size=16, show_progress_bar=False)
                if pairs
                else []
            )
            ordered = sorted(zip(selected, scores), key=lambda item: -float(item[1]))
            # Scores rank relevance; they are not calibrated factual confidence.
            # The Agent's evidence verifier decides whether a reply is supported.
            chosen = [by_id[source] for source, _ in ordered][:limit]
            return chosen, {
                "mode": "hybrid",
                "keyword_candidates": len(keyword),
                "vector_candidates": len(vector),
                "reranked": len(pairs),
                "keyword_ranking": [e["source_id"] for e in keyword],
                "vector_ranking": vector,
                "rerank_scores": {source: float(value) for source,value in ordered},
            }


_backend = None
_initialization = threading.Lock()


def backend():
    global _backend
    with _initialization:
        if _backend is None:
            _backend = HybridIndex()
    return _backend


async def search(context, entries, query):
    global _available
    if os.getenv("RAG_ENABLED", "0") != "1":
        return rank(entries, query), {"mode": "bm25"}
    if not entries:
        return [], {
            "mode": "hybrid",
            "keyword_candidates": 0,
            "vector_candidates": 0,
            "reranked": 0,
        }
    try:
        result = await asyncio.wait_for(
            asyncio.to_thread(lambda: backend().search(context, entries, query)), 20
        )
        _available = True
        return result
    except Exception:
        _available = False
        # Deliberately visible fallback, never claim semantic retrieval succeeded.
        return rank(entries, query), {"mode": "bm25_degraded", "reason": "RAG_UNAVAILABLE"}


_available = False


def warmup():
    global _available
    instance = backend()
    with instance.connection() as db:
        db.execute("SELECT 1 FROM rag_manifests LIMIT 0")
    _available = True


def status():
    enabled = os.getenv("RAG_ENABLED", "0") == "1"
    return {
        "mode": "hybrid" if enabled and _available else "bm25_degraded" if enabled else "bm25",
        "embedding_model": "BAAI/bge-small-zh-v1.5" if enabled else None,
        "reranker_model": "BAAI/bge-reranker-base" if enabled else None,
        "storage": "pgvector" if enabled else None,
        "local": True,
    }
