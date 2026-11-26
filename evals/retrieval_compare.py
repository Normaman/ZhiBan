"""Compare lexical/hybrid retrieval on labeled synthetic cases, without LLM calls."""

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]


def docker_run(args):
    # Only the two disposable-index credentials enter the evaluation container.
    values = dict(line.split("=", 1) for line in (ROOT / "infra/.env").read_text().splitlines()
                  if "=" in line and not line.startswith("#"))
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    env = {**os.environ, "RAG_EVAL_ADMIN_PASSWORD": values["RAG_ADMIN_PASSWORD"],
           "RAG_DB_PASSWORD": values["RAG_DB_PASSWORD"]}
    command = ["docker", "run", "--rm", "--network", "pdd-copilot_default", "--read-only",
               "--user", f"{getattr(os,'getuid',lambda:1000)()}:{getattr(os,'getgid',lambda:1000)()}", "--cap-drop=ALL",
               "--security-opt=no-new-privileges", "--env", "RAG_EVAL_ADMIN_PASSWORD",
               "--env", "RAG_DB_PASSWORD", "--env", "RAG_DB_HOST=rag-postgres",
               "--env", "RAG_DB_PORT=5432", "--env", "RAG_DB_USER=rag_app",
               "--env", "HF_HOME=/models", "--env", "HF_HUB_OFFLINE=1", "--env", "RAG_DEVICE=cpu",
               "--mount", f"type=bind,src={ROOT / 'evals'},dst=/app/evals,readonly",
               "--mount", f"type=bind,src={args.dataset.resolve()},dst=/input/cases.jsonl,readonly",
               "--mount", f"type=bind,src={output.parent},dst=/output",
               "--mount", "type=volume,src=pdd-copilot_rag-models,dst=/models,readonly",
               "--tmpfs", "/tmp:rw,noexec,nosuid,size=128m", "pdd-copilot-agent-service",
               "/app/.venv/bin/python", "-m", "evals.retrieval_compare", "--dataset", "/input/cases.jsonl",
               "--output", f"/output/{output.name}", "--split", args.split, "--limit", str(args.limit)]
    subprocess.run(command, env=env, check=True)


@contextmanager
def isolated_index():
    import psycopg
    from psycopg import sql
    import copilot.knowledge

    name = "copilot_eval_" + uuid.uuid4().hex[:16]
    common = {"host": os.environ["RAG_DB_HOST"], "port": os.getenv("RAG_DB_PORT", "5432"), "connect_timeout": 5}
    admin = {**common, "user": "rag_admin", "password": os.environ["RAG_EVAL_ADMIN_PASSWORD"]}
    created = False
    try:
        with psycopg.connect(**admin, dbname="postgres", autocommit=True) as db:
            db.execute(sql.SQL("CREATE DATABASE {} OWNER rag_app").format(sql.Identifier(name)))
            created = True
        with psycopg.connect(**admin, dbname=name) as db:
            db.execute((Path(copilot.knowledge.__file__).parent / "vector_schema.sql").read_text())
            db.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON rag_chunks,rag_manifests TO rag_app")
        os.environ["RAG_DB_NAME"] = name
        yield
    finally:
        if created:
            with psycopg.connect(**admin, dbname="postgres", autocommit=True) as db:
                db.execute(sql.SQL("DROP DATABASE {}").format(sql.Identifier(name)))


def scores(found, expected):
    ids = [entry["source_id"] for entry in found[:5]]
    gold = set(expected)
    gains = [int(source in gold) for source in ids]
    dcg = sum(gain / math.log2(i + 2) for i, gain in enumerate(gains))
    ideal = sum(1 / math.log2(i + 2) for i in range(min(5, len(gold))))
    return {"recall_at_5": sum(gains) / len(gold), "precision_at_5": sum(gains) / 5,
            "mrr": next((1 / (i + 1) for i, gain in enumerate(gains) if gain), 0),
            "ndcg_at_5": dcg / ideal if ideal else 0, "sources": ids}


def evaluate(args):
    sys.path.insert(0, str(ROOT / "services/agent-service"))
    from evals.dataset import load
    from evals.case_data import context_for
    from copilot.knowledge.hybrid import HybridIndex
    from copilot.knowledge.retrieval import rank
    from copilot.knowledge.model_catalog import MODELS

    cases = [case for case in load(args.dataset)
             if case.expected.retrieval_sources and case.provenance.kind == "synthetic"
             and (args.split == "all" or case.split == args.split)][:args.limit]
    if not cases:
        raise ValueError("NO_LABELED_SYNTHETIC_RETRIEVAL_CASES")
    rows = []
    with isolated_index():
        index = HybridIndex()  # Exclude one-time model loading from query latency.
        for case in cases:
            context = context_for(case)
            entries = [entry for entry in context.get("knowledge", [])
                       if "response_guidelines" not in entry.get("topics", [])]
            if not set(case.expected.retrieval_sources) <= {entry["source_id"] for entry in entries}:
                raise ValueError(f"RETRIEVAL_GOLD_OUTSIDE_SCOPE:{case.id}")
            context.update(shop_id="synthetic-evaluation", knowledge=entries,
                           knowledge_version=hashlib.sha256(json.dumps(entries,sort_keys=True).encode()).hexdigest())
            result = {"case_id": case.id, "split": case.split, "candidate_count": len(entries)}
            for mode in ("bm25", "hybrid"):
                started = time.monotonic()
                found = rank(entries, case.question) if mode == "bm25" else index.search(context, entries, case.question)[0]
                result[mode] = {**scores(found, case.expected.retrieval_sources),
                                "duration_ms": round((time.monotonic() - started) * 1000)}
            rows.append(result)
    summaries = {}
    for mode in ("bm25", "hybrid"):
        summaries[mode] = {key: sum(row[mode][key] for row in rows) / len(rows)
                           for key in ("recall_at_5", "precision_at_5", "mrr", "ndcg_at_5", "duration_ms")}
    report = {"created_at": datetime.now(timezone.utc).isoformat(), "cases": len(rows),
              "dataset_sha256": hashlib.sha256(args.dataset.read_bytes()).hexdigest(), "models": MODELS,
              "model_calls": 0, "temporary_index_deleted": True,
              "scope": "synthetic retrieval ranking only; first-index latency included; no generated-answer scoring",
              "single_candidate_cases": sum(row["candidate_count"] <= 1 for row in rows),
              "precision_denominator": 5,
              "summary": summaries, "results": rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with os.fdopen(os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
    print(json.dumps({"cases": len(rows), "summary": summaries, "model_calls": 0}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", action="store_true")
    parser.add_argument("--dataset", type=Path, default=ROOT / "evals/agent_cases.jsonl")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--split", choices=("dev", "test", "all"), default="test")
    parser.add_argument("--limit", type=int, default=100)
    args = parser.parse_args()
    if not 1 <= args.limit <= 500:
        parser.error("--limit must be 1..500")
    if args.output.exists():
        parser.error("Output exists; choose a new output path")
    docker_run(args) if args.docker else evaluate(args)


if __name__ == "__main__":
    main()
