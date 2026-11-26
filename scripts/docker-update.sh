#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"
compose=(docker compose --env-file infra/.env -f infra/compose.yml)
if [[ -f infra/.env ]] && [[ "$(sed -n 's/^RAG_ENABLED=//p' infra/.env | tail -n 1)" == '1' ]]; then
  compose+=(-f infra/compose.rag.yml)
fi

if [[ $# -eq 0 ]]; then
  echo '用法：bash scripts/docker-update.sh web|core-service|agent-service|camoufox-gateway|all [其他应用服务...]' >&2
  exit 2
fi
if [[ "$1" == 'all' ]]; then
  if [[ $# -ne 1 ]]; then
    echo 'all 不能和其他服务名一起使用' >&2
    exit 2
  fi
  set -- core-service agent-service web
fi
for service in "$@"; do
  case "$service" in
    web|core-service|agent-service|camoufox-gateway) ;;
    *) echo "不支持的应用服务：$service" >&2; exit 2 ;;
  esac
done

# One fixed rollback slot per service, captured before replacing the image tag.
for service in "$@"; do
  if image_id="$("${compose[@]}" images -q "$service")" && [[ -n "$image_id" ]]; then
    docker image tag "$image_id" "pdd-copilot-$service:rollback"
  fi
done
"${compose[@]}" build "$@"
bash scripts/docker-cache.sh clean
"${compose[@]}" up -d --no-build --no-deps "$@"
