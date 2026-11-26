"""Camoufox lease API and single-use Playwright Firefox WebSocket relay."""

import asyncio
import hmac
import logging
import os
from contextlib import asynccontextmanager, suppress
from urllib.parse import unquote, urlsplit

from fastapi import Depends, FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from lifecycle import GatewayError, Instances
from pydantic import BaseModel, ConfigDict, Field, field_validator
from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed

log = logging.getLogger("camoufox.gateway")


class FetchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    proxy: str | None = Field(default=None, max_length=2048, repr=False)
    # Correlation only: never forwarded into browser options or used as login data.
    loginSessionHint: str | None = Field(default=None, max_length=128, repr=False)

    @field_validator("proxy")
    @classmethod
    def validate_proxy(cls, value):
        if value is not None:
            try:
                address = urlsplit(value)
                if (
                    address.scheme not in {"http", "https", "socks5"}
                    or not address.hostname
                    or not address.port
                    or address.query
                    or address.fragment
                    or address.path not in {"", "/"}
                ):
                    raise ValueError()
            except Exception:
                raise ValueError("invalid proxy URL") from None
        return value

    def playwright_proxy(self) -> dict | None:
        if self.proxy is None:
            return None
        address = urlsplit(self.proxy)
        host = f"[{address.hostname}]" if ":" in address.hostname else address.hostname
        result = {"server": f"{address.scheme}://{host}:{address.port}"}
        if address.username is not None:
            result["username"] = unquote(address.username)
        if address.password is not None:
            result["password"] = unquote(address.password)
        return result


def number(name: str, default: int, minimum: int, maximum: int) -> int:
    value = int(os.environ.get(name, str(default)))
    if not minimum <= value <= maximum:
        raise RuntimeError(f"invalid {name}")
    return value


@asynccontextmanager
async def lifespan(app: FastAPI):
    token = os.environ.get("CAMOUFOX_GATEWAY_TOKEN", "")
    if len(token) < 32:
        raise RuntimeError("CAMOUFOX_GATEWAY_TOKEN must contain at least 32 characters")
    base = os.environ.get("CAMOUFOX_PUBLIC_WS_BASE", "ws://127.0.0.1:8000")
    uri = urlsplit(base)
    if (
        uri.scheme not in {"ws", "wss"}
        or not uri.hostname
        or uri.username is not None
        or uri.query
        or uri.fragment
        or uri.path not in {"", "/"}
    ):
        raise RuntimeError("invalid CAMOUFOX_PUBLIC_WS_BASE")
    manager = Instances(
        base,
        number("CAMOUFOX_INSTANCE_TTL", 120, 30, 3600),
        number("CAMOUFOX_MAX_INSTANCES", 2, 1, 32),
        number("CAMOUFOX_DISCONNECT_GRACE", 2, 0, 10),
    )
    app.state.manager, app.state.token = manager, token
    collector = asyncio.create_task(manager.collect())
    try:
        yield
    finally:
        collector.cancel()
        with suppress(asyncio.CancelledError):
            await collector
        await manager.close()


app = FastAPI(
    title="Camoufox Playwright Firefox Gateway",
    lifespan=lifespan,
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)


async def authorized(request: Request):
    actual = request.headers.get("authorization", "")
    if not hmac.compare_digest(actual.encode(), ("Bearer " + request.app.state.token).encode()):
        raise GatewayError(401, "UNAUTHORIZED", "gateway authentication required")


@app.exception_handler(GatewayError)
async def gateway_error(_request: Request, error: GatewayError):
    return JSONResponse(
        status_code=error.status, content={"code": error.code, "msg": error.message}
    )


# Strip Pydantic's reflected input: a bad proxy URL may include real credentials.
@app.exception_handler(RequestValidationError)
async def validation_error(_request: Request, _error: RequestValidationError):
    return JSONResponse(
        status_code=422, content={"code": "INVALID_REQUEST", "msg": "invalid request"}
    )


@app.get("/health")
async def health():
    return {"ok": True}


@app.post("/fetch", dependencies=[Depends(authorized)])
async def fetch(request: Request, body: FetchRequest):
    # Detect a client abort during startup; otherwise its unused browser waits for TTL.
    task = asyncio.create_task(
        request.app.state.manager.fetch(body.playwright_proxy(), body.loginSessionHint)
    )
    try:
        while not task.done():
            await asyncio.wait({task}, timeout=0.2)
            if await request.is_disconnected():
                if task.done() and not task.cancelled() and task.exception() is None:
                    request.app.state.manager.background(
                        request.app.state.manager.release(
                            task.result()["instanceId"], "http_disconnected"
                        )
                    )
                else:
                    task.cancel()
                    with suppress(asyncio.CancelledError):
                        await task
                raise GatewayError(499, "CLIENT_DISCONNECTED", "request disconnected")
        return await task
    finally:
        if not task.done():
            task.cancel()


@app.post("/release/{instance_id}", dependencies=[Depends(authorized)])
async def release(request: Request, instance_id: str):
    await request.app.state.manager.release(instance_id, "explicit_release")
    return {"ok": True}


@app.websocket("/relay/{instance_id}/{capability}")
async def websocket(websocket: WebSocket, instance_id: str, capability: str):
    manager = websocket.app.state.manager
    # The high-entropy URL capability authorizes WS only; never log the full path.
    instance = await manager.claim(instance_id, capability)
    if instance is None:
        await websocket.close(code=1008)
        return
    tasks = []
    try:
        headers = {
            name: value
            for name, value in websocket.headers.items()
            if name.lower().startswith("x-playwright-")
        }
        async with connect(
            instance.endpoint,
            additional_headers=headers,
            max_size=None,
            open_timeout=5,
            close_timeout=2,
        ) as upstream:
            await websocket.accept()
            log.info("instance_connected instanceId=%s", instance_id)

            async def to_browser():
                while True:
                    message = await websocket.receive()
                    if message["type"] == "websocket.disconnect":
                        return
                    payload = message.get("bytes")
                    if payload is None:
                        payload = message.get("text")
                    if payload is not None:
                        await upstream.send(payload)

            async def to_client():
                async for message in upstream:
                    if isinstance(message, bytes):
                        await websocket.send_bytes(message)
                    else:
                        await websocket.send_text(message)

            tasks = [asyncio.create_task(to_browser()), asyncio.create_task(to_client())]
            completed, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for task in completed:
                task.result()
    except (WebSocketDisconnect, ConnectionClosed):
        pass
    except Exception:
        # Protocol/socket exception messages can contain the secret WS URL.
        log.warning("instance_websocket_failed instanceId=%s", instance_id)
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        with suppress(Exception):
            await websocket.close(code=1001)
        manager.background(manager.disconnected(instance))
