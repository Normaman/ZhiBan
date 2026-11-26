"""Generate or supplement local-only credentials; never print or replace existing secret values."""

import argparse
import base64
import os
import secrets
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="infra/.env")
    parser.add_argument(
        "--upgrade",
        action="store_true",
        help="Add missing fields to an existing file; preserve all existing values",
    )
    args = parser.parse_args()
    path = Path(args.output)
    path.parent.mkdir(parents=True, exist_ok=True)
    values = {
        "DATABASE_PASSWORD": secrets.token_urlsafe(32),
        "REVIEWER_USER": "reviewer",
        "REVIEWER_PASSWORD": secrets.token_urlsafe(24),
        "INTERNAL_TOKEN": secrets.token_urlsafe(36),
        "METRICS_TOKEN": secrets.token_urlsafe(36),
        "REGISTRATION_INVITE": secrets.token_urlsafe(32),
        "REDIS_PASSWORD": secrets.token_urlsafe(32),
        "MODEL_ENCRYPTION_KEY": base64.b64encode(secrets.token_bytes(32)).decode(),
        "LLM_BASE_URL": "",
        "LLM_MODEL": "",
        "LLM_API_KEY": "",
    }
    upgrading = args.upgrade and path.exists()
    if upgrading:
        existing = dict(
            line.split("=", 1)
            for line in path.read_text().splitlines()
            if "=" in line and not line.startswith("#")
        )
        values = {**values, **existing}
        path.chmod(0o600)
    flags = os.O_WRONLY | (os.O_TRUNC if upgrading else os.O_CREAT | os.O_EXCL)
    descriptor = os.open(path, flags, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        stream.write("\n".join(f"{key}={value}" for key, value in values.items()) + "\n")
    print(f"Prepared {path}. Existing values preserved; read login credentials locally.")


if __name__ == "__main__":
    main()
