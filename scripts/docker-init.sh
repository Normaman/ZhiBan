#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ENV_FILE="$ROOT/infra/.env"

if [ -f "$ENV_FILE" ]; then
  printf '%s\n' 'infra/.env already exists; leaving existing credentials unchanged.'
  exit 0
fi

if ! command -v docker >/dev/null 2>&1; then
  printf '%s\n' 'Install Docker Desktop or Docker Engine with the Compose plugin first.' >&2
  exit 1
fi

docker run --rm \
  --user "$(id -u):$(id -g)" \
  --mount "type=bind,source=$ROOT/infra,target=/out" \
  python:3.12-alpine \
  python -c 'import base64, os, secrets; values={"DATABASE_PASSWORD":secrets.token_urlsafe(32),"REVIEWER_USER":"reviewer","REVIEWER_PASSWORD":secrets.token_urlsafe(24),"INTERNAL_TOKEN":secrets.token_urlsafe(36),"METRICS_TOKEN":secrets.token_urlsafe(36),"REGISTRATION_INVITE":secrets.token_urlsafe(32),"REDIS_PASSWORD":secrets.token_urlsafe(32),"MODEL_ENCRYPTION_KEY":base64.b64encode(secrets.token_bytes(32)).decode(),"LLM_BASE_URL":"","LLM_MODEL":"","LLM_API_KEY":""}; path="/out/.env"; fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600); stream=os.fdopen(fd,"w"); stream.write("\n".join(f"{k}={v}" for k,v in values.items())+"\n"); stream.close(); os.chmod(path,0o600)'

printf '%s\n' 'Created infra/.env with fresh local credentials. Read it locally for the initial admin login.'
