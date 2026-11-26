# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e
ARG AGENT_DEPENDENCIES=dependencies
FROM python:3.12-slim@sha256:5e2bdb1b0d542d5039cffb8181c65930093949194a65c365dfb1b0fe13a4fa5b AS dependencies
WORKDIR /app
RUN --mount=type=cache,id=pdd-pip,target=/root/.cache/pip,sharing=locked \
    pip install uv==0.13.6
ENV UV_PYTHON_DOWNLOADS=never UV_LINK_MODE=copy
RUN useradd --system --uid 10002 copilot

COPY pyproject.toml uv.lock ./
COPY services/agent-service/pyproject.toml services/agent-service/pyproject.toml
RUN --mount=type=cache,id=pdd-uv,target=/root/.cache/uv,sharing=locked \
    uv sync --locked --no-dev --no-install-workspace --package pdd-copilot-agent

FROM dependencies AS rag-dependencies
COPY services/agent-service/requirements-rag-cpu.lock /tmp/requirements-rag-cpu.lock
RUN --mount=type=cache,id=pdd-uv,target=/root/.cache/uv,sharing=locked \
    uv pip install --python /app/.venv/bin/python --torch-backend cpu --require-hashes -r /tmp/requirements-rag-cpu.lock

FROM ${AGENT_DEPENDENCIES} AS runtime
COPY services/agent-service/copilot services/agent-service/copilot
RUN --mount=type=cache,id=pdd-uv,target=/root/.cache/uv,sharing=locked \
    uv sync --locked --inexact --no-dev --no-editable --package pdd-copilot-agent \
      --reinstall-package pdd-copilot-agent
RUN /app/.venv/bin/python - <<'PY'
from importlib.util import find_spec
from pathlib import Path

source = Path("/app/services/agent-service/copilot")
installed = Path(find_spec("copilot").origin).parent
for path in source.rglob("*"):
    if path.is_file() and path.suffix in {".py", ".sql"}:
        assert (installed / path.relative_to(source)).read_bytes() == path.read_bytes(), path
PY
USER copilot
EXPOSE 8001
CMD ["/app/.venv/bin/uvicorn","copilot.app:app","--host","0.0.0.0","--port","8001","--workers","1","--no-access-log"]
