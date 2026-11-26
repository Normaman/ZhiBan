from unittest.mock import AsyncMock

import httpx
import pytest
from copilot.runtime_pool import RuntimePool
from copilot.settings import Settings
from fastapi import HTTPException


def settings(tmp_path):
    return Settings(
        token="test-internal-token-with-32-characters",
        shop_id="agent-service",
    )


def response(binding):
    return httpx.Response(200, json=binding, request=httpx.Request("GET", "http://core/"))


async def test_shop_runtime_has_no_platform_connector_or_credentials(tmp_path):
    base = settings(tmp_path)
    pool = RuntimePool(base)
    default = pool.start(base)
    default.core.get = AsyncMock(return_value=response({
        "id": "live-shop", "mode": "live", "platform_shop_id": "platform-fixture",
        "connector_account_id": "account-fixture", "binding_state": "bound",
    }))
    try:
        runtime = await pool.get("live-shop")
        assert not hasattr(runtime, "connector")
        assert not hasattr(runtime, "send")
        assert not hasattr(runtime.settings, "data_dir")
        assert not hasattr(runtime, "store")
        assert not hasattr(runtime.settings, "credential_key")
        assert not hasattr(runtime.settings, "auth_success")
    finally:
        await pool.close()


async def test_live_settings_reject_unbound_shop(tmp_path):
    pool = RuntimePool(settings(tmp_path))
    with pytest.raises(HTTPException) as error:
        pool.live_settings("shop", {
            "mode": "live", "binding_state": "unbound",
            "platform_shop_id": "platform", "connector_account_id": "account",
        })
    assert error.value.detail == "CONNECTOR_BINDING_MISMATCH"


async def test_distinct_process_pools_need_no_directory_lock(tmp_path):
    first, second = RuntimePool(settings(tmp_path)), RuntimePool(settings(tmp_path))
    try:
        one, two = first.start(first.settings), second.start(second.settings)
        assert one is not two
        assert not list(tmp_path.iterdir())
    finally:
        await first.close()
        await second.close()
