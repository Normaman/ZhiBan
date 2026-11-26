"""WSL development supervisor: PostgreSQL + Redis + Spring Boot + dl Python + React.

Credentials stay in .runtime/local.env. No project Python virtual environment is created.
"""

import argparse
import base64
import hashlib
import json
import os
import secrets
import shutil
import signal
import socket
import subprocess
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / ".runtime"
TOOLS = Path.home() / ".local/share/pdd-copilot/toolchains"
PG_PORT = 55439
REDIS_PORT = 56379
PROC = Path("/proc")
SERVICES = {"core", "agent", "web"}


def recorded_processes():
    path = RUNTIME / "pids.json"
    if not path.exists():
        return {}
    try:
        values = json.loads(path.read_text())
    except (OSError, ValueError) as error:
        raise RuntimeError("进程记录无法读取，请检查 .runtime/pids.json。") from error
    if not isinstance(values, dict):
        raise RuntimeError("进程记录格式不正确，请检查 .runtime/pids.json。")
    return {
        name: pid
        for name, pid in values.items()
        if name in SERVICES and type(pid) is int and pid > 1
    }


def owns_process(name, pid):
    """A reused PID must never count as our service or receive a stop signal."""
    try:
        process = PROC / str(pid)
        arguments = process.joinpath("cmdline").read_bytes().split(b"\0")
        arguments = [value.decode(errors="replace") for value in arguments if value]
        web = ROOT / "apps/web"
        expected = {
            "core": str(ROOT / "services/core-service/target/core-service-0.1.0.jar"),
            "agent": "copilot.app:app",
            "web": str(web / "node_modules/vite/bin/vite.js"),
        }
        return (
            expected.get(name) in arguments
            and process.joinpath("cwd").resolve(strict=True) == (web if name == "web" else ROOT)
        )
    except (OSError, RuntimeError):
        return False


def running_services():
    return {name: pid for name, pid in recorded_processes().items() if owns_process(name, pid)}


def prepare_start():
    active = running_services()
    if active:
        if set(active) == SERVICES:
            print("本地开发服务已在运行：http://localhost:5173；可用 status 查看状态。")
            return False
        raise SystemExit(
            "部分开发进程仍在运行（"
            + ", ".join(sorted(active))
            + "），请执行 python scripts/dev.py restart 恢复全部服务。"
        )
    path = RUNTIME / "pids.json"
    if path.exists():
        path.unlink()
        print("已清理失效的应用进程记录，继续启动。")
    for port in (8080, 8001, 5173):
        with socket.socket() as connection:
            connection.settimeout(0.3)
            if connection.connect_ex(("127.0.0.1", port)) == 0:
                raise SystemExit(
                    f"端口 {port} 已被未登记的服务占用；请确认对应进程后再启动。"
                )
    return True


def workspace(live_dir=None):
    path = RUNTIME / "workspace.json"
    saved = json.loads(path.read_text()) if path.exists() else {}
    return Path(live_dir or saved.get("live_data_dir") or RUNTIME / "pdd-live").expanduser().resolve()


def environment(live_dir=None):
    live_directory = workspace(live_dir)
    RUNTIME.mkdir(exist_ok=True, mode=0o700)
    path = RUNTIME / "local.env"
    values = {}
    if path.exists():
        values = dict(
            line.split("=", 1)
            for line in path.read_text().splitlines()
            if "=" in line and not line.startswith("#")
        )
    defaults = {
        "DATABASE_PASSWORD": secrets.token_urlsafe(32),
        "REVIEWER_USER": "reviewer",
        "REVIEWER_PASSWORD": secrets.token_urlsafe(24),
        "INTERNAL_TOKEN": secrets.token_urlsafe(36),
        "METRICS_TOKEN": secrets.token_urlsafe(36),
        "REGISTRATION_INVITE": secrets.token_urlsafe(32),
        "REDIS_PASSWORD": secrets.token_urlsafe(32),
        "MODEL_ENCRYPTION_KEY": base64.b64encode(secrets.token_bytes(32)).decode(),
        "LLM_BASE_URL": "",
        "LLM_MODEL": "",
        "LLM_API_KEY": "",
        "LLM_MAX_ROUNDS": "4",
        "AGENT_TOTAL_TIMEOUT": "70",
    }
    for key, value in defaults.items():
        values.setdefault(key, value)
    path.write_text("\n".join(f"{key}={value}" for key, value in values.items()) + "\n")
    path.chmod(0o600)
    env = os.environ.copy()
    env.update(values)
    for key in (
        "CREDENTIAL_KEY",
        "CREDENTIAL_KEY_FILE",
        "PDD_AUTH_SUCCESS_RESULT",
        "PDD_SEND_CONFIRMED",
        "PDD_JAVA_CONNECTOR_ENABLED",
        "PDD_JAVA_SEND_ENABLED",
        "DL_PYTHON",
    ):
        env.pop(key, None)
    for key in [
        "LLM_BASE_URL",
        "LLM_MODEL",
        "LLM_API_KEY",
        "LLM_MAX_ROUNDS",
        "AGENT_TOTAL_TIMEOUT",
    ]:
        if key in os.environ:
            env[key] = os.environ[key]
    env.update(
        {
            "JAVA_HOME": str(TOOLS / "jdk"),
            "DATABASE_URL": f"jdbc:postgresql://127.0.0.1:{PG_PORT}/copilot",
            "DATABASE_USER": "copilot",
            "SHOP_ID": "initial-shop",
            "WORKSPACE_DATABASE": "copilot",
            "LIVE_SEND_ENABLED": "false",
            "LIVE_SEND_CONVERSATION_ID": "",
            "LIVE_SEND_EXPIRES_AT": "0",
            "REDIS_ENABLED": "true",
            "REDIS_HOST": "127.0.0.1",
            "REDIS_PORT": str(REDIS_PORT),
            "CORE_URL": "http://127.0.0.1:8080",
            "AGENT_URL": "http://127.0.0.1:8001",
            "CONNECTOR_REGISTRY_FILE": str(RUNTIME / "connectors.json"),
            "PDD_DATA_DIR": str(RUNTIME / "pdd-live"),
            "DEBUG": "false",
            "ALLOW_LOCAL_MODEL_ENDPOINTS": "true",
        }
    )
    binding_file = live_directory / "connection.json"
    if binding_file.is_file():
        binding = json.loads(binding_file.read_text())
        scope = hashlib.sha256(
            (binding["platform_shop_id"] + ":" + binding["account_id"]).encode()
        ).hexdigest()[:16]
        database = "copilot_live_" + scope
        env.update(
            {
                "SHOP_ID": "pdd-" + scope,
                "PDD_DATA_DIR": str(live_directory),
                "WORKSPACE_DATABASE": database,
                "DATABASE_URL": f"jdbc:postgresql://127.0.0.1:{PG_PORT}/{database}",
            }
        )
    env.update(
        {
            "RAG_DB_NAME": env["WORKSPACE_DATABASE"],
            "RAG_DB_USER": env["DATABASE_USER"],
            "RAG_DB_PASSWORD": env["DATABASE_PASSWORD"],
            "RAG_DB_HOST": "127.0.0.1",
            "RAG_DB_PORT": str(PG_PORT),
        }
    )
    send_scope = RUNTIME / "live-send-scope.json"
    if send_scope.exists():
        grant = json.loads(send_scope.read_text())
        if (
            grant.get("shop_id") == env["SHOP_ID"]
            and grant.get("conversation_id")
            and (
                (grant.get("persistent") is True and grant.get("expires_at") == -1)
                or grant.get("expires_at", 0) > int(time.time() * 1000)
            )
        ):
            env.update(
                {
                    "LIVE_SEND_ENABLED": "true",
                    "LIVE_SEND_CONVERSATION_ID": grant["conversation_id"],
                    "LIVE_SEND_EXPIRES_AT": str(grant["expires_at"]),
                }
            )
    env["PATH"] = (
        os.pathsep.join(str(TOOLS / name / "bin") for name in ["jdk", "maven", "node"])
        + os.pathsep
        + env["PATH"]
    )
    return env


def run(command, *, env=None, input=None):
    result = subprocess.run(command, cwd=ROOT, env=env, input=input, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(
            f"Command failed: {Path(command[0]).name}. Check prerequisites and .runtime logs."
        )
    return result.stdout.strip()


def postgres_binary(name):
    found = shutil.which(name)
    if found:
        return found
    candidates = sorted(Path("/usr/lib/postgresql").glob(f"*/bin/{name}"), reverse=True)
    if not candidates:
        raise RuntimeError("Install PostgreSQL server and client in WSL first.")
    return str(candidates[0])


def databases(env):
    data = RUNTIME / "postgres"
    pg_ctl = postgres_binary("pg_ctl")
    if not (data / "PG_VERSION").exists():
        run(
            [
                postgres_binary("initdb"),
                "-D",
                str(data),
                "--auth-local=trust",
                "--auth-host=scram-sha-256",
            ]
        )
    state = subprocess.run([pg_ctl, "-D", str(data), "status"], capture_output=True)
    if state.returncode:
        run(
            [
                pg_ctl,
                "-D",
                str(data),
                "-l",
                str(RUNTIME / "postgres.log"),
                "-o",
                f"-p {PG_PORT} -h 127.0.0.1 -k {RUNTIME}",
                "start",
            ]
        )
    psql = [
        postgres_binary("psql"),
        "-h",
        str(RUNTIME),
        "-p",
        str(PG_PORT),
        "-d",
        "postgres",
        "-v",
        "ON_ERROR_STOP=1",
    ]
    exists = run(psql + ["-tAc", "SELECT 1 FROM pg_roles WHERE rolname='copilot'"])
    if not exists:
        password = env["DATABASE_PASSWORD"].replace("'", "''")
        run(psql, input=f"CREATE ROLE copilot LOGIN PASSWORD '{password}';\n")
    database = env["WORKSPACE_DATABASE"]
    exists = run(psql + ["-tAc", f"SELECT 1 FROM pg_database WHERE datname='{database}'"])
    if not exists:
        run(psql, input=f"CREATE DATABASE {database} OWNER copilot;\n")
    redis = TOOLS / "redis-7.4.2/src/redis-server"
    if not redis.exists():
        raise RuntimeError("Redis server not found; install Redis in WSL or use Compose.")
    cli = str(redis.with_name("redis-cli"))
    redis_env = dict(env, REDISCLI_AUTH=env["REDIS_PASSWORD"])
    ready = subprocess.run([cli, "-p", str(REDIS_PORT), "ping"], env=redis_env, capture_output=True)
    if ready.returncode:
        directory = RUNTIME / "redis"
        directory.mkdir(exist_ok=True)
        config = RUNTIME / "redis.conf"
        config.write_text(
            f"bind 127.0.0.1\nport {REDIS_PORT}\nprotected-mode yes\ndaemonize yes\nappendonly yes\ndir {directory}\nlogfile {RUNTIME / 'redis.log'}\npidfile {RUNTIME / 'redis.pid'}\nrequirepass {env['REDIS_PASSWORD']}\n"
        )
        config.chmod(0o600)
        run([str(redis), str(config)])


def wait_for(url, timeout=60):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with opener.open(url, timeout=2) as response:
                if response.status == 200:
                    return
        except Exception:
            pass
        time.sleep(0.4)
    raise RuntimeError(f"Service did not become healthy: {url}; check .runtime logs.")


def start(build=True, live_dir=None):
    live_directory = workspace(live_dir)
    python = Path(os.environ.get("DL_PYTHON", Path.home() / "miniconda3/envs/dl/bin/python"))
    if not python.exists():
        raise RuntimeError(
            "Set DL_PYTHON to the Python executable in your existing dl environment."
        )
    env = environment(live_directory)
    run([str(python), "-c", "import fastapi, langgraph, httpx, prometheus_client"], env=env)
    databases(env)
    jar = ROOT / "services/core-service/target/core-service-0.1.0.jar"
    if build or not jar.exists():
        with (RUNTIME / "build.log").open("w") as log:
            result = subprocess.run(
                [
                    str(TOOLS / "maven/bin/mvn"),
                    "-q",
                    "-f",
                    "services/core-service/pom.xml",
                    "-DskipTests",
                    "package",
                ],
                cwd=ROOT,
                env=env,
                stdout=log,
                stderr=subprocess.STDOUT,
            )
        if result.returncode:
            raise RuntimeError("Spring build failed; see .runtime/build.log")
    web = ROOT / "apps/web"
    if not (web / "node_modules").exists():
        run([str(TOOLS / "node/bin/npm"), "ci", "--prefix", str(web)], env=env)
    commands = {
        "core": ([str(TOOLS / "jdk/bin/java"), "-jar", str(jar)], ROOT),
        "agent": (
            [
                str(python),
                "-m",
                "uvicorn",
                "copilot.app:app",
                "--host",
                "127.0.0.1",
                "--port",
                "8001",
                "--workers",
                "1",
                "--no-access-log",
            ],
            ROOT,
        ),
        "web": (
            [
                str(TOOLS / "node/bin/node"),
                str(web / "node_modules/vite/bin/vite.js"),
                "--host",
                "127.0.0.1",
                "--strictPort",
            ],
            web,
        ),
    }
    pids = {}
    for name, (command, cwd) in commands.items():
        with (RUNTIME / f"{name}.log").open("w") as log:
            process = subprocess.Popen(
                command,
                cwd=cwd,
                env=env,
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
        pids[name] = process.pid
        (RUNTIME / "pids.json").write_text(json.dumps(pids))
        (RUNTIME / "pids.json").chmod(0o600)
        if name == "core":
            wait_for("http://127.0.0.1:8080/actuator/health")
            # Wait until bootstrap and membership initialization finish.
            deadline = time.monotonic() + 30
            ready = False
            if __package__:
                from .browser_session import authenticated_opener
            else:
                from browser_session import authenticated_opener
            opener = None
            while time.monotonic() < deadline:
                request = urllib.request.Request("http://127.0.0.1:8080/api/v1/bootstrap")
                try:
                    if opener is None:
                        opener = authenticated_opener("http://127.0.0.1:8080", env["REVIEWER_USER"], env["REVIEWER_PASSWORD"])
                    with opener.open(request, timeout=2) as response:
                        state = json.load(response)
                        ready = any(
                            shop["id"] == env["SHOP_ID"] for shop in state["shops"]
                        )
                        if ready:
                            break
                except Exception:
                    pass
                time.sleep(0.2)
            if not ready:
                raise RuntimeError("Core workspace did not finish initialization")
    (RUNTIME / "pids.json").write_text(json.dumps(pids))
    for url in [
        "http://127.0.0.1:8080/actuator/health",
        "http://127.0.0.1:8001/health",
        "http://127.0.0.1:5173",
    ]:
        wait_for(url)
    (RUNTIME / "workspace.json").write_text(
        json.dumps({"live_data_dir": str(live_directory)})
    )
    (RUNTIME / "workspace.json").chmod(0o600)
    print(
        "Ready: http://localhost:5173 | Java 8080 | Python dl 8001 | PostgreSQL 55439 | Redis 56379"
    )
    print("Login and registration invitation: read .runtime/local.env locally. Logs: .runtime/")
    print("已启用单店铺运行空间。真实发送授权状态：" + ("已启用" if env["LIVE_SEND_ENABLED"] == "true" else "未启用"))


def stop():
    path = RUNTIME / "pids.json"
    if not path.exists():
        return
    pids = running_services()
    for name, pid in pids.items():
        if owns_process(name, pid):
            try:
                os.kill(pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline and running_services():
        time.sleep(0.2)
    if running_services():
        raise RuntimeError("部分开发进程未能停止，已保留进程记录；请检查 .runtime 日志。")
    path.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["start", "stop", "restart", "status"])
    parser.add_argument("--no-build", action="store_true")
    parser.add_argument(
        "--live-data-dir", type=Path, help="Saved login directory; default .runtime/pdd-live"
    )
    args = parser.parse_args()
    if args.action in {"stop", "restart"}:
        stop()
    if args.action in {"start", "restart"}:
        if not prepare_start():
            return
        try:
            start(not args.no_build, args.live_data_dir)
        except BaseException:
            stop()
            raise
    if args.action == "status":
        print("platform data directory", workspace())
        if (RUNTIME / "pids.json").exists() and not running_services():
            print("应用进程记录已失效，下次 start 会自动清理并重新启动。")
        for port, path in [(8080, "/actuator/health"), (8001, "/health"), (5173, "/")]:
            try:
                wait_for(f"http://127.0.0.1:{port}{path}", timeout=2)
                print(port, "healthy")
            except RuntimeError:
                print(port, "unavailable")


if __name__ == "__main__":
    main()
