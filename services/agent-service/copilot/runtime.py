"""Execute Agent requests; Core owns durable results and cross-replica leases."""

import asyncio
import os

import httpx
from fastapi import HTTPException

from .clients.core import AgentBusinessClient
from .contracts import RunRequest
from .graph import DraftGraph
from .metrics import JOBS
from .model_configuration import snapshot


class Runtime:
    def __init__(self, settings):
        os.environ["LANGSMITH_TRACING"] = "false"
        os.environ["LANGCHAIN_TRACING_V2"] = "false"
        self.settings = settings
        self.core = httpx.AsyncClient(
            base_url=settings.core_url,
            headers={"X-Internal-Token": settings.token, "X-Shop-Id": settings.shop_id},
            timeout=15,
            follow_redirects=False,
            trust_env=False,
        )
        self.business = AgentBusinessClient(self.core, settings.shop_id)
        self.graph = DraftGraph(settings, self.business)
        self.generation_slots = asyncio.Semaphore(settings.generation_concurrency)

    async def generate(self, request: RunRequest):
        try:
            return await asyncio.wait_for(self._with_capacity(request), timeout=100)
        except asyncio.TimeoutError:
            raise HTTPException(504, "AGENT_EXECUTION_TIMEOUT") from None

    async def _with_capacity(self, request):
        async with self.generation_slots:
            return await self._generate(request)

    async def _generate(self, request):
        if request.shop_id != self.settings.shop_id:
            raise HTTPException(409, "RUN_SCOPE_MISMATCH")
        path = f"/internal/v1/agent-runs/{request.run_id}/execution"
        while True:
            response = await self.core.post(f"{path}/claim", json=request.model_dump())
            response.raise_for_status()
            execution = response.json()
            if execution["state"] == "completed":
                return execution["result"]
            if execution["state"] == "claimed":
                break
            if execution["state"] != "running":
                raise ValueError("INVALID_EXECUTION_STATE")
            await asyncio.sleep(0.2)
        lease = execution["lease_id"]
        try:
            response = await self.core.get(f"/internal/v1/agent-runs/{request.run_id}/context")
            response.raise_for_status()
            context = response.json()
            if (
                any(
                    str(context.get(k) or "") != str(v)
                    for k, v in {
                        "shop_id": request.shop_id,
                        "conversation_id": request.conversation_id,
                        "message_id": request.message_id,
                        "knowledge_version": request.knowledge_version,
                    }.items()
                )
                or request.shop_id != self.settings.shop_id
            ):
                raise HTTPException(409, "RUN_SCOPE_MISMATCH")
            configuration = await self.core.get(
                f"/internal/v1/shops/{self.settings.shop_id}/model-config"
            )
            configuration.raise_for_status()
            effective = configuration.json()
            run_settings = snapshot(self.settings, effective)
            graph = (
                self.graph
                if run_settings is self.settings
                else DraftGraph(run_settings, self.business)
            )
            result = await graph.generate(context)
            result["model_config_version"] = effective.get("configuration_id")
            response = await self.core.post(
                f"{path}/complete", json={"lease_id": lease, "result": result}
            )
            response.raise_for_status()
            JOBS.labels("generate", result["outcome"]).inc()
            return result
        finally:
            # A lost response may already have committed a result. Core never clears it here.
            try:
                await self.core.post(f"{path}/release", json={"lease_id": lease}, timeout=2)
            except httpx.HTTPError:
                pass
