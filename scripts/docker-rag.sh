#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
compose=(docker compose --env-file infra/.env -f infra/compose.yml -f infra/compose.rag.yml)
case "${1:-status}" in
  build) "${compose[@]}" build core-service agent-service web ;;
  up)
    "${compose[@]}" up -d --no-build --wait core-service agent-service web rag-postgres rag-models
    if grep -q '^RAG_ENABLED=' infra/.env; then
      sed -i 's/^RAG_ENABLED=.*/RAG_ENABLED=1/' infra/.env
    else
      printf '\nRAG_ENABLED=1\n' >> infra/.env
    fi
    ;;
  status) "${compose[@]}" ps -a ;;
  *) echo 'Usage: bash scripts/docker-rag.sh build|up|status' >&2; exit 2 ;;
esac
