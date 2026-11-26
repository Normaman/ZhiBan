"""Local dataset curation UI; edits files, never starts a model or a shop connector."""

import argparse
import base64
import binascii
import io
import json
import secrets
import sys
import zipfile
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "services/agent-service"))

from fastapi import FastAPI  # noqa: E402
from fastapi.responses import FileResponse, JSONResponse, Response  # noqa: E402
from pydantic import ValidationError  # noqa: E402
from starlette.middleware.trustedhost import TrustedHostMiddleware  # noqa: E402

from evals.case_data import context_for, editable_data  # noqa: E402
from evals.curation import Conflict, Workspace, fingerprint, sha, slug, write_json  # noqa: E402
from evals.dataset import Case  # noqa: E402

ASSETS = Path(__file__).with_name("web")


def create_app(workspace, token=None):
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=["localhost", "127.0.0.1"])
    token = token or secrets.token_urlsafe(32)

    @app.middleware("http")
    async def local_request(request, call_next):
        if request.method not in {"GET", "HEAD"}:
            origin = request.headers.get("origin")
            if origin and urlsplit(origin).netloc != request.headers.get("host"):
                return JSONResponse({"detail": "只允许本地同源页面修改"}, status_code=403)
            if not secrets.compare_digest(request.headers.get("x-curation-token", ""), token):
                return JSONResponse({"detail": "页面令牌失效，请刷新"}, status_code=403)
            if not request.headers.get("content-type", "").startswith("application/json"):
                return JSONResponse({"detail": "仅支持JSON请求"}, status_code=415)
            if len(await request.body()) > 8 * 1024 * 1024:
                return JSONResponse({"detail": "单次资料不能超过8MB"}, status_code=413)
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
        )
        response.headers["X-Content-Type-Options"] = "nosniff"
        return response

    @app.exception_handler(ValueError)
    async def invalid(request, error):
        if isinstance(error, ValidationError):
            detail = "；".join(
                ".".join(map(str, e["loc"])) + ": " + e["msg"] for e in error.errors()
            )
        else:
            detail = str(error)
        return JSONResponse(
            {"detail": detail}, status_code=409 if isinstance(error, Conflict) else 422
        )

    @app.exception_handler(FileExistsError)
    async def existing(request, error):
        return JSONResponse({"detail": "该导出版本已存在，请换一个版本名"}, status_code=409)

    @app.exception_handler(KeyError)
    async def missing(request, error):
        return JSONResponse({"detail": "缺少必填字段：" + str(error)}, status_code=422)

    @app.get("/")
    @app.get("/review.html")
    def page():
        return FileResponse(ASSETS / "index.html")

    @app.get("/workbench.js")
    def javascript():
        return FileResponse(ASSETS / "workbench.js", media_type="text/javascript")

    @app.get("/workbench.css")
    def stylesheet():
        return FileResponse(ASSETS / "workbench.css", media_type="text/css")

    @app.get("/api/workspace")
    def state():
        with workspace.lock:
            current = workspace.state
            cases = []
            for raw in current["cases"]:
                c = Case.model_validate(raw)
                original = workspace.base.get(c.id)
                rows = {
                    name: [r for (identity, _), r in index.items() if identity == c.id]
                    for name, index in workspace.rows.items()
                }
                cases.append(
                    {
                        "case": raw,
                        "rows": rows,
                        "regressed": any(
                            workspace.rows["before"][(c.id, r["repeat"])]["score"]["passed"]
                            and not r["score"]["passed"]
                            for r in rows["after"]
                        ),
                        "sample": any(
                            (c.id, r["repeat"]) in workspace.sampled for r in rows["after"]
                        ),
                        "archived": c.id in current["archived"],
                        "edited": original is None or original.model_dump(mode="json") != raw,
                        "requires_rerun": original is None
                        or c.fixture != original.fixture
                        or c.input != original.input
                        or fingerprint(workspace.context_for(c)) != fingerprint(workspace.context_for(original)),
                        "original_context": workspace.context_for(original) if original else {},
                        "original_case": original.model_dump(mode="json") if original else None,
                    }
                )
            versions = [
                json.loads(p.read_text())
                for p in sorted((workspace.root / "versions").glob("*/manifest.json"))
            ]
            return {
                "token": token,
                "revision": current["revision"],
                "cases": cases,
                "report_hashes": workspace.report_hashes,
                "adjudications": current["adjudications"],
                "reviews": current["reviews"],
                "journal": current["journal"],
                "versions": versions,
                "stats": {v: workspace.corrected_stats(v) for v in ["before", "after"]},
                "workspace_path": str(workspace.root),
                "legacy_storage_key": workspace.legacy_storage_key,
            }

    @app.put("/api/cases/{identity}")
    def save_case(identity: str, body: dict):
        case = Case.model_validate(body["case"])
        if case.id != identity:
            raise ValueError("用例编号与请求不一致")
        revision = workspace.save_case(
            body["revision"],
            case.model_dump(mode="json"),
            body.get("reason", ""),
            body.get("create", False),
        )
        return {"revision": revision}

    @app.post("/api/cases/{identity}/archive")
    def archive(identity: str, body: dict):
        return {"revision": workspace.archive(body["revision"], identity, body.get("reason", ""))}

    @app.get("/api/cases/{identity}/template")
    async def template(identity: str):
        raw = next((c for c in workspace.state["cases"] if c["id"] == identity), None)
        if raw is None:
            raise ValueError("用例不存在")
        return (await editable_data(Case.model_validate(raw), workspace.fixtures_module)).model_dump(mode="json")

    @app.post("/api/template")
    async def draft_template(body: dict):
        return (await editable_data(Case.model_validate(body["case"]))).model_dump(mode="json")

    @app.post("/api/reviews/import-legacy")
    def legacy_reviews(body: dict):
        return workspace.import_legacy_reviews(body["revision"], body["ratings"])

    @app.post("/api/adjudications")
    def adjudicate(body: dict):
        return {
            "revision": workspace.save_adjudication(
                body["revision"],
                body["version"],
                body["case_id"],
                body["repeat"],
                body["result_fingerprint"],
                body["report_hash"],
                body["check_overrides"],
                body.get("reason", ""),
                body.get("category", "uncertain"),
            )
        }

    @app.post("/api/reviews")
    def review(body: dict):
        return {
            "revision": workspace.save_review(
                body["revision"],
                body["version"],
                body["case_id"],
                body["repeat"],
                body["result_fingerprint"],
                body["labels"],
            )
        }

    @app.get("/api/reviews/{version}/download")
    def reviews_download(version: str):
        if version not in workspace.reports:
            raise ValueError("报告版本不存在")
        records = [
            r
            for k, r in workspace.state["reviews"].items()
            if k.startswith(version + ":")
            and all(
                type(r.get(f)) is bool
                for f in ["factually_correct", "grounded", "usable_without_edit"]
            )
        ]
        return Response(
            "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in records),
            media_type="application/x-ndjson",
            headers={"Content-Disposition": f'attachment; filename="{version}-reviews.jsonl"'},
        )

    @app.post("/api/datasets/export")
    def export(body: dict):
        return workspace.export(body["revision"], body["version"], body.get("reason", ""))

    @app.get("/api/datasets/{version}/download")
    def dataset_download(version: str):
        directory = workspace.root / "versions" / slug(version)
        if not (directory / "manifest.json").is_file():
            raise ValueError("数据集版本不存在")
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as archive:
            for name in [
                "cases.jsonl",
                "manifest.json",
                "changes.json",
                "adjudications.json",
                "reviews.json",
            ]:
                archive.writestr(name, (directory / name).read_bytes())
        return Response(
            stream.getvalue(),
            media_type="application/zip",
            headers={"Content-Disposition": f'attachment; filename="{version}.zip"'},
        )

    @app.post("/api/import/preview")
    def preview(body: dict):
        with workspace.lock:
            workspace.check_revision(body["revision"])
            return workspace.preview_import(body["cases"])

    @app.post("/api/import/archive")
    def read_archive(body: dict):
        if not isinstance(body["archive_base64"], str):
            raise ValueError("数据包编码须为字符串")
        try:
            data = base64.b64decode(body["archive_base64"], validate=True)
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                if len(archive.namelist()) != len(set(archive.namelist())):
                    raise ValueError("数据包含重复文件名")
                info = archive.getinfo("cases.jsonl")
                if info.file_size > 8 * 1024 * 1024:
                    raise ValueError("解压后的用例不能超过8MB")
                raw = archive.read(info)
                if "manifest.json" in archive.namelist():
                    if archive.getinfo("manifest.json").file_size > 65536:
                        raise ValueError("版本说明过大")
                    manifest = json.loads(archive.read("manifest.json"))
                    import hashlib

                    if not isinstance(manifest, dict) or hashlib.sha256(
                        raw
                    ).hexdigest() != manifest.get("dataset_sha256"):
                        raise ValueError("数据包用例与版本指纹不一致")
                values = [
                    json.loads(line) for line in raw.decode("utf-8").splitlines() if line.strip()
                ]
        except (binascii.Error, zipfile.BadZipFile, KeyError, UnicodeError, RuntimeError) as error:
            raise ValueError("无法读取数据包，请使用本工具导出的 ZIP") from error
        return {"cases": values}

    @app.post("/api/import")
    def import_cases(body: dict):
        return {
            "revision": workspace.import_cases(
                body["revision"], body["cases"], body["digest"], body.get("reason", "")
            )
        }

    @app.post("/api/regrade")
    def regrade(body: dict):
        with workspace.lock:
            return workspace.regrade(body["version"])

    @app.get("/api/regrades/{identity}/download")
    def regrade_download(identity: str):
        directory = workspace.root / "regrades" / slug(identity)
        if not (directory / "manifest.json").is_file():
            raise ValueError("重算结果不存在")
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as archive:
            for path in directory.rglob("*.json"):
                archive.writestr(str(path.relative_to(directory)), path.read_bytes())
        return Response(
            stream.getvalue(),
            media_type="application/zip",
            headers={"Content-Disposition": f'attachment; filename="{identity}.zip"'},
        )

    return app


def restore_dataset_path(workspace, paths, explicit_dataset=False):
    """Resume an existing review against its frozen baseline, never a newer dataset."""
    state_path = workspace / "workspace.json"
    baseline = workspace / "base-dataset.jsonl"
    if explicit_dataset or not state_path.is_file() or not baseline.is_file():
        return paths
    state = json.loads(state_path.read_text())
    report_hashes = {name: sha(paths[name]) for name in ("before", "after")}
    if state.get("report_hashes") != report_hashes:
        raise ValueError("该工作区已绑定其他报告，请使用新工作区目录")
    if sha(baseline) != state.get("base_dataset_sha256"):
        raise ValueError("工作区冻结基线已被修改，请从备份恢复 base-dataset.jsonl")
    return {**paths, "dataset": baseline}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=Path, default=ROOT / ".runtime/eval-curation/main")
    parser.add_argument("--dataset", type=Path)
    parser.add_argument("--before", type=Path)
    parser.add_argument("--after", type=Path)
    parser.add_argument("--port", type=int, default=5190)
    args = parser.parse_args()
    config_path = args.workspace / "config.json"
    config = json.loads(config_path.read_text()) if config_path.is_file() else {}
    defaults = {
        "dataset": "evals/agent_cases.jsonl",
        "before": ".runtime/evals/generalization-before-20261109/report.json",
        "after": ".runtime/evals/generalization-after-final-20261109/report.json",
    }
    paths = {
        name: getattr(args, name) or ROOT / config.get(name, default)
        for name, default in defaults.items()
    }
    if any(not paths[name].is_file() for name in ("before", "after")):
        parser.error("找不到数据集或报告，请通过 --dataset、--before、--after 指定本机文件")
    try:
        paths = restore_dataset_path(args.workspace, paths, explicit_dataset=args.dataset is not None)
        if not paths["dataset"].is_file():
            parser.error("找不到基础数据集，请通过 --dataset 指定与报告匹配的数据集文件")
        workspace = Workspace(args.workspace, **paths)
    except ValueError as error:
        parser.error(str(error))
    if not config_path.is_file():
        write_json(config_path, {k: str(v.resolve()) for k, v in paths.items()})
    import uvicorn

    print(f"本地评测数据校对：http://localhost:{args.port}/review.html", flush=True)
    uvicorn.run(create_app(workspace), host="127.0.0.1", port=args.port, access_log=False)


if __name__ == "__main__":
    main()
