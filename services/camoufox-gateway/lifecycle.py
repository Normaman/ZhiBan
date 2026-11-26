"""Bounded instance ownership, process-tree cleanup and cancellation-safe release."""

import asyncio
import hashlib
import json
import logging
import os
import secrets
import shutil
import sys
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path

import psutil

log = logging.getLogger("camoufox.gateway")


class GatewayError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(code)
        self.status, self.code, self.message = status, code, message


@dataclass
class Instance:
    instance_id: str
    capability: str
    directory: Path
    expires: float
    process: asyncio.subprocess.Process | None = None
    spawn_task: asyncio.Task | None = None
    start_task: asyncio.Task | None = None
    cleanup_task: asyncio.Task | None = None
    endpoint: str = ""
    claimed: bool = False
    closing: bool = False
    # psutil.Process remembers creation time, preventing signals to reused PIDs.
    processes: dict[tuple[int, float], psutil.Process] = field(default_factory=dict)


def remember(instance: Instance, pid: int) -> None:
    try:
        process = psutil.Process(pid)
        for child in [process, *process.children(recursive=True)]:
            instance.processes[(child.pid, child.create_time())] = child
    except psutil.Error:
        pass


def kill_remaining(instance: Instance) -> None:
    """Firefox can lead a separate process group; killing only the launcher leaks it."""
    # A launcher can be SIGKILLed between spawning Firefox and sending READY.
    # Its orphan is no longer discoverable via parent PID; an instance tag in the
    # owned process environment covers this gap without killing unrelated Firefox.
    for process in psutil.process_iter(["pid"]):
        try:
            if os.name == "posix" and process.uids().real != os.getuid():
                continue
            if process.environ().get("CAMOUFOX_INSTANCE_ID") == instance.instance_id:
                remember(instance, process.pid)
        except psutil.Error:
            pass
    # Graceful EOF has already had seven seconds. Freeze known parents before
    # collecting descendants again so the launcher cannot race cleanup by forking.
    for _ in range(3):
        before = len(instance.processes)
        for process in list(instance.processes.values()):
            try:
                if process.is_running() and process.status() != psutil.STATUS_ZOMBIE:
                    process.suspend()
                    remember(instance, process.pid)
            except psutil.NoSuchProcess:
                pass
        if len(instance.processes) == before:
            break
    processes = list(instance.processes.values())
    for process in reversed(processes):
        try:
            if process.is_running() and process.status() != psutil.STATUS_ZOMBIE:
                process.kill()
        except psutil.NoSuchProcess:
            pass
    # asyncio owns waitpid() for the launcher itself; don't let psutil reap it.
    root = instance.process.pid if instance.process is not None else None
    _, alive = psutil.wait_procs([p for p in processes if p.pid != root], timeout=1)
    alive.extend(p for p in processes if p.pid == root)
    if any(p.is_running() and p.status() != psutil.STATUS_ZOMBIE for p in alive):
        raise GatewayError(503, "RELEASE_FAILED", "browser process cleanup failed")


class Instances:
    def __init__(self, public_ws_base: str, ttl: int, capacity: int, grace: float):
        self.public_ws_base = public_ws_base.rstrip("/")
        self.ttl, self.capacity, self.grace = ttl, capacity, grace
        self.items: dict[str, Instance] = {}
        self.lock = asyncio.Lock()
        self.tasks: set[asyncio.Task] = set()
        self.stopping = False

    def background(self, coroutine) -> asyncio.Task:
        task = asyncio.create_task(coroutine)
        self.tasks.add(task)

        def done(completed):
            self.tasks.discard(completed)
            if not completed.cancelled() and completed.exception() is not None:
                log.error("gateway_background_failed type=%s", type(completed.exception()).__name__)

        task.add_done_callback(done)
        return task

    async def fetch(self, proxy: dict | None, login_session_hint: str | None = None) -> dict:
        async with self.lock:
            if self.stopping:
                raise GatewayError(503, "SHUTTING_DOWN", "gateway is shutting down")
            if len(self.items) >= self.capacity:
                raise GatewayError(503, "CAPACITY_EXCEEDED", "browser capacity exhausted")
            instance = Instance(
                "inst_" + secrets.token_hex(16),
                secrets.token_urlsafe(32),
                Path(tempfile.mkdtemp(prefix="camoufox-instance-")),
                time.monotonic() + 45,
            )
            self.items[instance.instance_id] = instance
            instance.start_task = self.background(self._start(instance, proxy))
        try:
            await asyncio.wait_for(asyncio.shield(instance.start_task), timeout=45)
            if instance.closing:
                raise GatewayError(503, "INSTANCE_CLOSED", "browser was released during startup")
            instance.expires = time.monotonic() + self.ttl
            # A caller could put personal data in the hint: correlate its hash only.
            hint_hash = hashlib.sha256((login_session_hint or "").encode()).hexdigest()[:16]
            log.info(
                "instance_ready instanceId=%s loginHintHash=%s", instance.instance_id, hint_hash
            )
            return {
                "instanceId": instance.instance_id,
                "wsEndpoint": f"{self.public_ws_base}/relay/{instance.instance_id}/{instance.capability}",
                "protocol": "playwright-firefox",
                "ttlSeconds": self.ttl,
            }
        except BaseException as error:
            # Keep cleanup alive independently of an aborted HTTP request.
            cleanup = self.background(self.release(instance.instance_id, "startup_failed"))
            if isinstance(error, asyncio.CancelledError):
                raise
            await asyncio.shield(cleanup)
            if isinstance(error, GatewayError):
                raise
            code = "START_TIMEOUT" if isinstance(error, TimeoutError) else "START_FAILED"
            raise GatewayError(
                504 if code == "START_TIMEOUT" else 500, code, "start firefox failed"
            ) from None

    async def _start(self, instance: Instance, proxy: dict | None) -> None:
        # subprocess spawn is itself shielded so cancellation cannot lose its PID.
        environment = dict(os.environ)
        environment.pop("CAMOUFOX_GATEWAY_TOKEN", None)
        environment["CAMOUFOX_INSTANCE_ID"] = instance.instance_id
        # Browser profiles and driver scratch files must stay inside this lease so
        # forced termination also permits removal of Cookies/cache from disk.
        for name in ("TMPDIR", "TMP", "TEMP"):
            environment[name] = str(instance.directory)
        instance.spawn_task = asyncio.create_task(
            asyncio.create_subprocess_exec(
                sys.executable,
                str(Path(__file__).with_name("worker.py")),
                str(instance.directory),
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
                start_new_session=os.name != "nt",
                limit=65536,
                env=environment,
            )
        )
        instance.process = await asyncio.shield(instance.spawn_task)
        process = instance.process
        remember(instance, process.pid)
        if instance.closing:
            raise GatewayError(503, "INSTANCE_CLOSED", "browser was released during startup")
        process.stdin.write((json.dumps({"proxy": proxy}) + "\n").encode())
        await process.stdin.drain()
        while line := await process.stdout.readline():
            if not line.startswith(b"GATEWAY_READY "):
                continue  # Never copy launcher output: it can contain private options.
            ready = json.loads(line[len(b"GATEWAY_READY ") :])
            endpoint = ready["wsEndpoint"]
            from urllib.parse import urlsplit

            address = urlsplit(endpoint)
            if address.scheme != "ws" or address.hostname not in {"127.0.0.1", "localhost"}:
                raise GatewayError(500, "ENDPOINT_INVALID", "invalid internal browser endpoint")
            instance.endpoint = endpoint
            remember(instance, int(ready["browserPid"]))
            self.background(self._monitor(instance))
            return
        raise GatewayError(500, "START_FAILED", "start firefox failed")

    async def _monitor(self, instance: Instance) -> None:
        # Drain stdout continuously to avoid a blocked child; no raw content logging.
        try:
            while await instance.process.stdout.read(8192):
                pass
            await instance.process.wait()
        finally:
            if not instance.closing:
                await self.release(instance.instance_id, "launcher_exited")

    async def claim(self, instance_id: str, capability: str) -> Instance | None:
        async with self.lock:
            instance = self.items.get(instance_id)
            if (
                instance is None
                or instance.closing
                or instance.claimed
                or not instance.endpoint
                or instance.expires <= time.monotonic()
                or not secrets.compare_digest(capability.encode(), instance.capability.encode())
            ):
                return None
            instance.claimed = True
            return instance

    async def disconnected(self, instance: Instance) -> None:
        log.info("instance_disconnected instanceId=%s", instance.instance_id)
        await asyncio.sleep(self.grace)
        await self.release(instance.instance_id, "websocket_disconnected")

    async def release(self, instance_id: str, reason: str) -> None:
        async with self.lock:
            instance = self.items.get(instance_id)
            if instance is None:
                return  # Idempotent, including a second release after disconnect GC.
            if instance.cleanup_task is None or (
                instance.cleanup_task.done() and instance.cleanup_task.exception() is not None
            ):
                instance.closing = True
                instance.cleanup_task = self.background(self._cleanup(instance, reason))
            task = instance.cleanup_task
        await asyncio.shield(task)

    async def _cleanup(self, instance: Instance, reason: str) -> None:
        try:
            if instance.spawn_task is not None:
                try:
                    instance.process = await asyncio.shield(instance.spawn_task)
                except Exception:
                    pass  # Spawn failed before creating a child; remove its lease.
            elif instance.start_task is not None:
                instance.start_task.cancel()  # It has not spawned anything yet.
            process = instance.process
            if process is not None:
                # Snapshot descendants BEFORE requesting exit; after reparenting they
                # may no longer be discoverable from the original launcher PID.
                remember(instance, process.pid)
                if process.stdin is not None:
                    process.stdin.close()  # Node EOF -> BrowserServer.close().
                try:
                    await asyncio.wait_for(process.wait(), timeout=7)
                except TimeoutError:
                    remember(instance, process.pid)
                await asyncio.to_thread(kill_remaining, instance)
                try:
                    await asyncio.wait_for(process.wait(), timeout=2)
                except TimeoutError:
                    raise GatewayError(503, "RELEASE_FAILED", "launcher cleanup failed") from None
            shutil.rmtree(instance.directory)
            async with self.lock:
                self.items.pop(instance.instance_id, None)
            log.info("instance_released instanceId=%s reason=%s", instance.instance_id, reason)
        except Exception:
            # Retain a failed lease/capacity slot so GC and a later release can retry.
            log.error("instance_release_failed instanceId=%s", instance.instance_id)
            raise GatewayError(503, "RELEASE_FAILED", "browser process cleanup failed") from None

    async def collect(self) -> None:
        while True:
            await asyncio.sleep(1)
            for instance in list(self.items.values()):
                if instance.process is not None and not instance.closing:
                    remember(instance, instance.process.pid)
                if instance.expires <= time.monotonic() or (
                    instance.cleanup_task is not None
                    and instance.cleanup_task.done()
                    and instance.cleanup_task.exception() is not None
                ):
                    self.background(self.release(instance.instance_id, "ttl_or_cleanup_retry"))

    async def close(self) -> None:
        self.stopping = True
        await asyncio.gather(
            *[self.release(key, "shutdown") for key in list(self.items)], return_exceptions=True
        )
        # Only background grace timers/monitors remain; owned children are closed.
        pending = list(self.tasks)
        for task in pending:
            task.cancel()
        await asyncio.gather(*pending, return_exceptions=True)
