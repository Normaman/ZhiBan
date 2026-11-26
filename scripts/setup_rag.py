"""Prepare local derived pgvector tables; models stay local and no shop data is sent out."""

import argparse
import os
import subprocess
from pathlib import Path

from dev import ROOT, environment, postgres_binary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--download-models",
        action="store_true",
        help="Cache the two public BAAI models; no business data is uploaded",
    )
    args = parser.parse_args()
    os.environ.update(environment())
    if args.download_models:
        from huggingface_hub import snapshot_download

        for model in ("BAAI/bge-small-zh-v1.5", "BAAI/bge-reranker-base"):
            snapshot_download(
                model, allow_patterns=["*.json", "*.txt", "*.safetensors", "1_Pooling/*"]
            )
    schema = (
        Path(__file__).resolve().parents[1]
        / "services/agent-service/copilot/knowledge/vector_schema.sql"
    )
    result = subprocess.run(
        [
            postgres_binary("psql"),
            "-h",
            str(ROOT / ".runtime"),
            "-p",
            "55439",
            "-d",
            os.environ["RAG_DB_NAME"],
            "-v",
            "ON_ERROR_STOP=1",
        ],
        input=schema.read_text()
        + "\nALTER TABLE rag_manifests OWNER TO copilot; ALTER TABLE rag_chunks OWNER TO copilot;",
        text=True,
        capture_output=True,
    )
    if result.returncode:
        raise RuntimeError("RAG_SCHEMA_SETUP_FAILED")
    print(
        "Local pgvector schema ready. Set RAG_ENABLED=1 in .runtime/local.env and restart after models are cached."
    )


if __name__ == "__main__":
    main()
