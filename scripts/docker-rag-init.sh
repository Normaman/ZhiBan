#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
docker run --rm --user "$(id -u):$(id -g)" \
  --mount "type=bind,source=$ROOT/infra,target=/out" \
  python:3.12-alpine python -c 'import os,secrets; from pathlib import Path; p=Path("/out/.env"); raw=p.read_text(); names={line.split("=",1)[0] for line in raw.splitlines() if "=" in line}; missing=[name for name in ("RAG_ADMIN_PASSWORD","RAG_DB_PASSWORD") if name not in names]; fd=os.open(p,os.O_WRONLY|os.O_APPEND); stream=os.fdopen(fd,"w"); stream.write(("\n" if raw and not raw.endswith("\n") else "")+"".join(name+"="+secrets.token_urlsafe(32)+"\n" for name in missing)); stream.close(); os.chmod(p,0o600); print("RAG credentials ready; existing values preserved.")'
