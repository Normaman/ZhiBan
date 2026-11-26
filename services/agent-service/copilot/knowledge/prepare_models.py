"""Explicit one-shot public model download, never executed during Agent requests."""

import os
from pathlib import Path

from huggingface_hub import snapshot_download
from .model_catalog import MODELS


def main():
    root = Path(os.environ.get("HF_HOME", "/models"))
    root.mkdir(parents=True, exist_ok=True)
    for model,revision in MODELS.items():
        try:
            cached = Path(snapshot_download(model, revision=revision, local_files_only=True))
            complete = all((cached / name).is_file() for name in ("config.json", "model.safetensors", "tokenizer.json"))
        except Exception:
            complete = False
        if not complete:
            snapshot_download(model, revision=revision,
                              allow_patterns=["*.json", "*.txt", "*.model", "*.safetensors", "1_Pooling/*"])
    # Only this dedicated model volume is writable to the root init container.
    for path in [*root.rglob("*"), root]:
        if not path.is_symlink():
            os.chown(path, 10002, 10002)
    print("RAG model cache prepared", flush=True)


if __name__ == "__main__":
    main()
