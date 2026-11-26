"""File-based local API for editing the project's evaluation datasets."""

import hashlib
import json
import os
import secrets
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, JSONResponse, Response
from pydantic import ValidationError
from starlette.middleware.trustedhost import TrustedHostMiddleware

from evals.dataset import Case, validate_cases
from evals.knowledge_dataset import KnowledgeCase

ROOT = Path(__file__).resolve().parents[2]
ASSETS = Path(__file__).with_name("web")
SUITES = {
    "agent": {
        "label": "Agent 行为",
        "filename": "agent_cases.jsonl",
        "source": ROOT / "evals" / "agent_cases.jsonl",
        "model": Case,
    },
    "knowledge": {
        "label": "知识库解析",
        "filename": "knowledge_ingestion_cases.jsonl",
        "source": ROOT / "evals" / "knowledge_ingestion_cases.jsonl",
        "model": KnowledgeCase,
    },
}


def initialize_data(data_dir: Path):
    data_dir.mkdir(parents=True, exist_ok=True)
    for key, suite in SUITES.items():
        target = data_dir / suite["filename"]
        if not target.exists():
            if key == "agent":
                # Upgrade existing workspaces from the original 80-case suite.
                # Keep local edits for matching IDs and add the 216 older cases.
                legacy = data_dir / "agent_optimization_cases.jsonl"
                if legacy.exists():
                    source_rows = [
                        suite["model"].model_validate_json(line).model_dump(mode="json")
                        for line in suite["source"].read_text(encoding="utf-8").splitlines()
                        if line.strip()
                    ]
                    local_rows = [
                        suite["model"].model_validate_json(line).model_dump(mode="json")
                        for line in legacy.read_text(encoding="utf-8").splitlines()
                        if line.strip()
                    ]
                    local_by_id = {row["id"]: row for row in local_rows}
                    merged = [local_by_id.pop(row["id"], row) for row in source_rows]
                    merged.extend(local_by_id.values())
                    target.write_text(
                        "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in merged),
                        encoding="utf-8",
                    )
                else:
                    shutil.copyfile(suite["source"], target)
            else:
                shutil.copyfile(suite["source"], target)
            target.chmod(0o600)


def _path(data_dir: Path, suite: str) -> Path:
    if suite not in SUITES:
        raise ValueError("未知测试集")
    return data_dir / SUITES[suite]["filename"]


def _read(path: Path, suite: str):
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            item = SUITES[suite]["model"].model_validate_json(line)
            rows.append(item.model_dump(mode="json"))
        except (ValidationError, ValueError, json.JSONDecodeError) as error:
            raise ValueError(f"第 {number} 行格式错误：{error}") from error
    _validate_collection(suite, rows)
    return rows


def _validate_collection(suite: str, rows: list[dict]):
    model = SUITES[suite]["model"]
    cases = [model.model_validate(row) for row in rows]
    if not cases or len({case.id for case in cases}) != len(cases):
        raise ValueError("测试集不能为空，且编号不能重复")
    if suite == "agent":
        validate_cases(cases)
        return

    scenario_splits = {}
    group_splits = {}
    for case in cases:
        if scenario_splits.setdefault(case.scenario, case.split) != case.split:
            raise ValueError(f"{case.id}：同一场景不能同时出现在开发集和测试集")
        if case.group_id and group_splits.setdefault(case.group_id, case.split) != case.split:
            raise ValueError(f"{case.id}：同一资料组不能同时出现在开发集和测试集")
        docs = {(doc.source_id, doc.version): doc for doc in case.documents}
        if len(docs) != len(case.documents):
            raise ValueError(f"{case.id}：文档版本重复")
        for entry in case.expected_entries:
            key = (entry.source_id, entry.version)
            if key not in docs or docs[key].state == "deleted":
                raise ValueError(f"{case.id}：期望条目引用的资料不存在或已删除")
            for source in entry.also_from:
                if (source.get("source_id"), source.get("version")) not in docs:
                    raise ValueError(f"{case.id}：条目来源引用的资料不存在")
        if not set(case.expected_deleted_sources) <= {doc.source_id for doc in case.documents}:
            raise ValueError(f"{case.id}：删除目标不在输入资料中")
        if case.expected_conflict and not case.expected_review_required:
            raise ValueError(f"{case.id}：冲突用例必须标记需要人工复核")
        if case.expected_error and case.expected_entries:
            raise ValueError(f"{case.id}：解析失败用例不能同时包含期望条目")


def _revision(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _write(path: Path, suite: str, rows: list[dict]):
    _validate_collection(suite, rows)
    encoded = "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    backup = path.with_name(f"{path.stem}.{stamp}.backup.jsonl")
    shutil.copyfile(path, backup)
    backup.chmod(0o600)
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def create_app(data_dir: Path, token: str):
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=["localhost", "127.0.0.1", "::1"])

    @app.middleware("http")
    async def local_only(request: Request, call_next):
        if request.method not in {"GET", "HEAD"}:
            origin = request.headers.get("origin")
            if origin and urlsplit(origin).netloc != request.headers.get("host"):
                return JSONResponse({"detail": "只允许本地同源操作"}, status_code=403)
            if not secrets.compare_digest(request.headers.get("x-studio-token", ""), token):
                return JSONResponse({"detail": "页面令牌已失效，请刷新页面"}, status_code=403)
            if not request.headers.get("content-type", "").startswith("application/json"):
                return JSONResponse({"detail": "仅支持 JSON 请求"}, status_code=415)
            if len(await request.body()) > 8 * 1024 * 1024:
                return JSONResponse({"detail": "单次导入不能超过 8MB"}, status_code=413)
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; "
            "frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
        )
        response.headers["X-Content-Type-Options"] = "nosniff"
        return response

    @app.exception_handler(ValueError)
    async def invalid(_request, error):
        detail = str(error)
        if isinstance(error, ValidationError):
            detail = "；".join(
                ".".join(map(str, item["loc"])) + "：" + item["msg"] for item in error.errors()
            )
        return JSONResponse({"detail": detail}, status_code=422)

    @app.get("/")
    def page():
        return FileResponse(ASSETS / "index.html")

    @app.get("/app.js")
    def javascript():
        return FileResponse(ASSETS / "app.js", media_type="text/javascript")

    @app.get("/app.css")
    def stylesheet():
        return FileResponse(ASSETS / "app.css", media_type="text/css")

    @app.get("/api/state")
    def state():
        suites = {}
        for key, metadata in SUITES.items():
            path = _path(data_dir, key)
            cases = _read(path, key)
            suites[key] = {
                "label": metadata["label"],
                "cases": cases,
                "revision": _revision(path),
                "counts": {
                    "total": len(cases),
                    "dev": sum(case["split"] == "dev" for case in cases),
                    "test": sum(case["split"] == "test" for case in cases),
                },
            }
        return {"token": token, "data_dir": str(data_dir), "suites": suites}

    @app.put("/api/suites/{suite}")
    def save_suite(suite: str, body: dict):
        path = _path(data_dir, suite)
        if body.get("revision") != _revision(path):
            return JSONResponse({"detail": "数据已被其他页面修改，请刷新后重试"}, status_code=409)
        rows = body.get("cases")
        if not isinstance(rows, list):
            raise ValueError("测试集内容必须是数组")
        normalized = [SUITES[suite]["model"].model_validate(row).model_dump(mode="json") for row in rows]
        _write(path, suite, normalized)
        return {"revision": _revision(path), "count": len(normalized)}

    @app.post("/api/suites/{suite}/import")
    def import_cases(suite: str, body: dict):
        path = _path(data_dir, suite)
        if body.get("revision") != _revision(path):
            return JSONResponse({"detail": "数据已被其他页面修改，请刷新后重试"}, status_code=409)
        incoming = body.get("cases")
        if not isinstance(incoming, list):
            raise ValueError("导入内容必须是测试用例数组")
        incoming = [SUITES[suite]["model"].model_validate(row).model_dump(mode="json") for row in incoming]
        if body.get("mode") == "replace":
            rows = incoming
        else:
            rows = _read(path, suite)
            by_id = {row["id"]: row for row in rows}
            for row in incoming:
                by_id[row["id"]] = row
            rows = list(by_id.values())
        _write(path, suite, rows)
        return {"revision": _revision(path), "count": len(rows)}

    @app.get("/api/suites/{suite}/export")
    def export_cases(suite: str):
        path = _path(data_dir, suite)
        content = "".join(
            json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n"
            for row in _read(path, suite)
        )
        return Response(
            content,
            media_type="application/x-ndjson",
            headers={"Content-Disposition": f'attachment; filename="{path.name}"'},
        )

    @app.post("/api/suites/knowledge/score")
    def score_knowledge(_body: dict):
        from evals.knowledge_dataset import evaluate

        return evaluate(_path(data_dir, "knowledge"))

    return app
