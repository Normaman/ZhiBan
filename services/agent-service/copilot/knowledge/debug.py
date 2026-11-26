"""Read-only diagnostics over the authorized published snapshot; no model generation."""

import time

from pydantic import BaseModel, Field
from typing import Literal

from .hybrid import search
from .retrieval import rank


class SearchRequest(BaseModel):
    context: dict
    query: str = Field(min_length=1, max_length=1000)
    source_type: Literal["product", "shop_policy"] | None = None
    topic: str | None = None
    goods_id: str | None = None
    mode: Literal["current", "bm25", "hybrid"] = "current"


async def inspect(request):
    started = time.monotonic()
    context = request.context
    entries = [e for e in context.get("knowledge", [])
               if (not request.source_type or e.get("source_type") == request.source_type)
               and (not request.topic or request.topic in e.get("topics", []))
               and (not request.goods_id or str(e.get("goods_id", "")) == request.goods_id)]
    if request.mode == "bm25":
        found = rank(entries, request.query)
        metadata = {"mode": "bm25"}
    else:
        found, metadata = await search(context, entries, request.query)
        if request.mode == "hybrid" and metadata["mode"] == "bm25":
            metadata = {"mode": "bm25_degraded", "reason": "RAG_DISABLED"}
    return {
        "knowledge_version": context.get("knowledge_version"),
        "requested_mode": request.mode,
        "retrieval": metadata,
        "scope_count": len(entries),
        "duration_ms": round((time.monotonic() - started) * 1000),
        "reason": None if found else "NO_MATCH_IN_SCOPE" if entries else "NO_ENTRIES_IN_SCOPE",
        "hits": [{"rank": i, **e, "source_version": context.get("knowledge_version")}
                 for i, e in enumerate(found, 1)],
    }
