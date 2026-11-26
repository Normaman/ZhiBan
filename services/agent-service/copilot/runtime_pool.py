"""Shop-scoped HTTP clients sharing a process-wide generation capacity."""

import asyncio
import re
from dataclasses import replace

from fastapi import HTTPException

from .runtime import Runtime


class RuntimePool:
    def __init__(self, settings):
        self.settings = settings
        self.runtimes = {}
        self.lock = asyncio.Lock()
        self.generation_slots = asyncio.Semaphore(settings.generation_concurrency)

    def start(self, settings):
        if settings.shop_id in self.runtimes:
            raise ValueError("AGENT_RUNTIME_ALREADY_EXISTS")
        settings.validate()
        runtime = Runtime(settings)
        runtime.generation_slots = self.generation_slots
        self.runtimes[settings.shop_id] = runtime
        return runtime

    async def stop_shop(self, shop):
        if shop == self.settings.shop_id:
            raise HTTPException(409, "DEFAULT_AGENT_RUNTIME_CANNOT_BE_REMOVED")
        async with self.lock:
            runtime = self.runtimes.pop(shop, None)
            if runtime is None:
                return {"stopped": False}
            await runtime.core.aclose()
            return {"stopped": True}

    def live_settings(self, shop, binding):
        if binding.get("binding_state") != "bound" or binding.get("mode") != "live":
            raise HTTPException(409, "CONNECTOR_BINDING_MISMATCH")
        return replace(
            self.settings,
            shop_id=shop,
            llm_url="",
            llm_model="",
            llm_key="",
        )

    async def get(self, shop):
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", shop):
            raise HTTPException(400, "INVALID_SHOP_ID")
        if shop in self.runtimes:
            return self.runtimes[shop]
        async with self.lock:
            if shop in self.runtimes:
                return self.runtimes[shop]
            if len(self.runtimes) >= 33:
                raise HTTPException(503, "AGENT_CAPACITY_REACHED")
            core = self.runtimes[self.settings.shop_id].core
            response = await core.get(
                f"/internal/v1/shops/{shop}/connector-binding", headers={"X-Shop-Id": shop}
            )
            response.raise_for_status()
            binding = response.json()
            if binding.get("id") != shop:
                raise HTTPException(409, "CONNECTOR_BINDING_MISMATCH")
            if binding.get("mode") == "live" and binding.get("binding_state") == "bound":
                settings = self.live_settings(shop, binding)
            else:
                raise HTTPException(409, "SHOP_NOT_CONNECTED")
            return self.start(settings)

    async def close(self):
        for runtime in self.runtimes.values():
            await runtime.core.aclose()
