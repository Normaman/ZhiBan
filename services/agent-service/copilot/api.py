"""HTTP authentication, request tracing, and thin route handlers."""

import re
import secrets
import time
import uuid

import httpx
from fastapi import Depends, Header, HTTPException, Request, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from .contracts import RunRequest
from .knowledge.documents import DocumentRequest
from .knowledge.import_service import preview
from .metrics import logger
from .model_configuration import ModelConfiguration, test_configuration


def register_routes(app, settings):
    async def authorize(x_internal_token: str = Header(default="")):
        if not secrets.compare_digest(settings.token, x_internal_token) or not settings.token:
            raise HTTPException(401, "UNAUTHORIZED")

    @app.middleware("http")
    async def trace(request: Request, call_next):
        trace_id = request.headers.get("X-Trace-Id", "")
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", trace_id):
            trace_id = str(uuid.uuid4())
        start = time.monotonic()
        response = await call_next(request)
        response.headers["X-Trace-Id"] = trace_id
        logger.info(
            "request_complete trace_id=%s method=%s status=%s duration_ms=%d",
            trace_id,
            request.method,
            response.status_code,
            (time.monotonic() - start) * 1000,
        )
        return response

    @app.exception_handler(ValueError)
    async def invalid(request, error):
        return Response(
            content='{"detail":"CONFLICT_OR_INVALID_STATE"}',
            status_code=409,
            media_type="application/json",
        )

    @app.exception_handler(httpx.HTTPError)
    async def downstream(request, error):
        return Response(
            content='{"detail":"CORE_UNAVAILABLE"}',
            status_code=503,
            media_type="application/json",
        )

    @app.get("/health")
    async def health():
        return {
            "status": "ok",
            "agent_mode": "model" if settings.model_configured else "extractive",
        }

    @app.get("/metrics", dependencies=[Depends(authorize)])
    async def metrics():
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    @app.post("/internal/v1/shops/{shop_id}/stop", dependencies=[Depends(authorize)])
    async def stop_shop(shop_id: str):
        return await app.state.pool.stop_shop(shop_id)

    @app.post("/internal/v1/knowledge/status", dependencies=[Depends(authorize)])
    async def knowledge_status():
        from .knowledge.hybrid import status

        return status()

    from .knowledge.debug import SearchRequest, inspect

    @app.post("/internal/v1/knowledge/search", dependencies=[Depends(authorize)])
    async def knowledge_search(input: SearchRequest, x_shop_id: str = Header(default="")):
        if not x_shop_id or input.context.get("shop_id") != x_shop_id:
            raise HTTPException(403,"KNOWLEDGE_SCOPE_MISMATCH")
        return await inspect(input)

    @app.post("/internal/v1/knowledge/preview", dependencies=[Depends(authorize)])
    async def document_preview(input: DocumentRequest):
        return await preview(input)

    @app.post("/internal/v1/agent-runs", dependencies=[Depends(authorize)])
    async def generate(input: RunRequest, x_shop_id: str = Header(default="")):
        if x_shop_id and x_shop_id != input.shop_id:
            raise HTTPException(409, "RUN_SCOPE_MISMATCH")
        return await (await app.state.pool.get(input.shop_id)).generate(input)

    @app.post("/internal/v1/model-config/test", dependencies=[Depends(authorize)])
    async def test_model(input: ModelConfiguration):
        return await test_configuration(settings, input)
