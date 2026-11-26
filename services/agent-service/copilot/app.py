"""Application factory and process lifecycle for the Agent worker."""

import asyncio
import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .api import register_routes
from .runtime_pool import RuntimePool
from .settings import Settings


def create_app(settings: Settings | None = None):
    settings = settings or Settings()

    @asynccontextmanager
    async def lifespan(app):
        logging.basicConfig(
            level=logging.INFO,
            format="%(asctime)s level=%(levelname)s logger=%(name)s %(message)s",
        )
        logging.getLogger("httpx").setLevel(logging.WARNING)
        settings.validate()
        if os.getenv("RAG_ENABLED", "0") == "1":
            from .knowledge.hybrid import warmup

            try:
                await asyncio.to_thread(warmup)
            except Exception:
                logging.getLogger(__name__).warning(
                    "retrieval_mode=bm25_degraded reason=RAG_UNAVAILABLE"
                )
        pool = RuntimePool(settings)
        runtime = pool.start(settings)
        app.state.runtime = runtime
        app.state.pool = pool
        try:
            yield
        finally:
            await pool.close()

    app = FastAPI(
        title="客服 Agent",
        version="0.1.0",
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    register_routes(app, settings)
    return app


app = create_app()
