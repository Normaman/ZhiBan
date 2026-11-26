"""A disposable vector database; never reuse the live knowledge index."""

import os
import subprocess
import uuid
from contextlib import contextmanager
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def local_values(path):
    if not path.is_file():
        return {}
    return dict(
        line.split("=", 1)
        for line in path.read_text().splitlines()
        if "=" in line and not line.startswith("#")
    )


@contextmanager
def retrieval_database(mode):
    prior = dict(os.environ)
    name = None
    try:
        os.environ["RAG_ENABLED"] = "1" if mode == "hybrid" else "0"
        if mode == "hybrid":
            from scripts.dev import postgres_binary

            values = local_values(ROOT / ".runtime/local.env")
            password = os.environ.get("DATABASE_PASSWORD") or values.get("DATABASE_PASSWORD")
            if not password:
                raise ValueError("LOCAL_RAG_DATABASE_PASSWORD_REQUIRED")

            def admin(database, sql):
                r = subprocess.run(
                    [
                        postgres_binary("psql"),
                        "-h",
                        str(ROOT / ".runtime"),
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
                    raise RuntimeError("EVAL_VECTOR_DATABASE_FAILED")

            name = "copilot_eval_" + uuid.uuid4().hex[:12]
            admin("postgres", "CREATE DATABASE " + name + " OWNER copilot")
            os.environ.update(
                RAG_DB_HOST="127.0.0.1",
                RAG_DB_PORT="55439",
                RAG_DB_NAME=name,
                RAG_DB_USER="copilot",
                RAG_DB_PASSWORD=password,
            )
            schema = (
                ROOT / "services/agent-service/copilot/knowledge/vector_schema.sql"
            ).read_text()
            admin(
                name,
                schema
                + "\nALTER TABLE rag_manifests OWNER TO copilot; ALTER TABLE rag_chunks OWNER TO copilot;",
            )
            from copilot.knowledge.hybrid import backend

            backend()  # Load local weights before individual case latency and tool timeouts.
        yield
    finally:
        try:
            if name:
                admin("postgres", "DROP DATABASE IF EXISTS " + name + " WITH (FORCE)")
        finally:
            os.environ.clear()
            os.environ.update(prior)
