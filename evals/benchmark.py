"""Run the production draft graph against synthetic read-only business fixtures."""

import argparse
import asyncio
import hashlib
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch
from urllib.parse import quote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "services/agent-service"))

from copilot.agent.queries import knowledge as tool_module  # noqa: E402
from copilot.agent.tool_executor import ToolExecutor  # noqa: E402
from copilot.clients.llm import LLMClient  # noqa: E402
from copilot.graph import DraftGraph  # noqa: E402
from copilot.settings import Settings  # noqa: E402

from evals.case_data import connector_for, context_for  # noqa: E402
from evals.dataset import fingerprint, load  # noqa: E402
from evals.scoring import compare, score, summary, write_report  # noqa: E402
from evals.storage import local_values, retrieval_database  # noqa: E402


class Budget:
    def __init__(self, maximum):
        self.maximum = maximum
        self.used = 0
        self.exhausted = False


class MeasuredClient(LLMClient):
    def __init__(self, settings, budget):
        super().__init__(settings)
        self.budget = budget
        self.requests = []

    async def chat(self, *args, **kwargs):
        if self.budget.used >= self.budget.maximum:
            self.budget.exhausted = True
            raise ValueError("EVALUATION_CALL_BUDGET_EXHAUSTED")
        self.budget.used += 1
        record = {"usage": None, "elapsed_seconds": None, "error_type": None}
        self.requests.append(record)
        started = time.monotonic()
        try:
            response = await super().chat(*args, **kwargs)
            record["usage"] = response.usage
            return response
        except BaseException as error:
            record["error_type"] = type(error).__name__
            raise
        finally:
            record["elapsed_seconds"] = round(time.monotonic() - started, 3)


def model_configuration(args):
    if args.model_source == "env":
        file_values = local_values(args.env_file)
        config = {
            "base_url": os.getenv("LLM_BASE_URL") or file_values.get("LLM_BASE_URL", ""),
            "model": os.getenv("LLM_MODEL") or file_values.get("LLM_MODEL", ""),
            "api_key": os.getenv("LLM_API_KEY") or file_values.get("LLM_API_KEY", ""),
            "max_rounds": int(os.getenv("LLM_MAX_ROUNDS") or file_values.get("LLM_MAX_ROUNDS", "4")),
            "timeout_seconds": int(os.getenv("AGENT_TOTAL_TIMEOUT") or file_values.get("AGENT_TOTAL_TIMEOUT", "70")),
        }
    else:
        import httpx

        if not args.shop_id:
            raise ValueError("ADMIN_MODEL_SOURCE_REQUIRES_SHOP_ID")
        url = urlsplit(args.core_url)
        if url.scheme != "https" and not (
            url.scheme == "http" and url.hostname in {"localhost", "127.0.0.1", "::1"}
        ):
            raise ValueError("ADMIN_URL_REQUIRES_HTTPS_OR_LOOPBACK")
        token = os.getenv("INTERNAL_TOKEN") or local_values(args.env_file).get("INTERNAL_TOKEN")
        if not token:
            raise ValueError("ADMIN_INTERNAL_TOKEN_REQUIRED")
        with httpx.Client(trust_env=False, follow_redirects=False, timeout=10) as client:
            response = client.get(
                args.core_url.rstrip("/")
                + "/internal/v1/shops/"
                + quote(args.shop_id, safe="")
                + "/model-config",
                headers={"X-Internal-Token": token, "X-Shop-Id": args.shop_id},
            )
            if not response.is_success:
                raise ValueError("ADMIN_MODEL_CONFIGURATION_UNAVAILABLE")
            config = response.json()
        if not config.get("enabled"):
            raise ValueError("ADMIN_MODEL_NOT_ENABLED")
    if not all(config.get(k) for k in ["base_url", "model", "api_key"]):
        raise ValueError("COMPLETE_MODEL_CONFIGURATION_REQUIRED")
    return config


async def evaluate(case, settings, budget, repeat):
    context = context_for(case)
    # Each case may encode a different immutable synthetic knowledge snapshot.
    # Give snapshots separate tenant namespaces so pgvector's content fingerprint
    # cannot mistake one case's fixture for a changed live version in another.
    context["shop_id"] = "synthetic-shop-" + case.id
    connector = connector_for(case)
    graph = DraftGraph(settings, connector)
    client = MeasuredClient(settings, budget)
    graph.agent.client = client
    tools, retrievals = [], []
    execute = ToolExecutor.execute
    search = tool_module.search

    async def measured_execute(executor, call):
        result = await execute(executor, call)
        payload = json.loads(result["content"])
        try:
            arguments = json.loads(call.function.arguments)
        except (TypeError, ValueError):
            arguments = {"invalid_json": True}
        try:
            effective = (
                executor.registry[call.function.name]
                .param_model.model_validate_json(call.function.arguments)
                .model_dump()
            )
        except Exception:
            effective = {"invalid_parameters": True}
        tools.append(
            {
                "name": call.function.name,
                "arguments": arguments,
                "effective_arguments": effective,
                "response": payload,
            }
        )
        return result

    async def measured_search(context, entries, query):
        found, trace = await search(context, entries, query)
        retrievals.append({"sources": [e["source_id"] for e in found[:5]], "mode": trace["mode"]})
        return found, trace

    started = time.monotonic()
    # Patches affect only this CLI process. Cases are sequential; batch subquestions
    # can still execute concurrently using the normal production graph.
    with (
        patch.object(ToolExecutor, "execute", measured_execute),
        patch.object(tool_module, "search", measured_search),
    ):
        result = await graph.generate(context)
    row = {
        "case_id": case.id,
        "scenario": case.scenario,
        "split": case.split,
        "repeat": repeat,
        "question": case.question,
        "expected": case.expected.model_dump(),
        "result": result,
        "elapsed_seconds": round(time.monotonic() - started, 3),
        "model_requests": client.requests,
        "tools": tools,
        "retrievals": retrievals,
        "connector_calls": connector.calls,
    }
    row["score"] = score(case, result, tools, retrievals)
    if os.getenv("RAG_ENABLED") == "1" and retrievals:
        available = all(r["mode"] == "hybrid" for r in retrievals)
        row["score"]["checks"].append(
            {"name": "requested_hybrid_available", "category": "retrieval", "passed": available}
        )
        row["score"]["passed"] &= available
    row["result_fingerprint"] = fingerprint(result)
    return row


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--dataset", type=Path, default=ROOT / "evals/agent_cases.jsonl")
    p.add_argument(
        "--validate", action="store_true", help="Validate synthetic fixtures without model requests"
    )
    p.add_argument("--model-source", choices=["env", "admin"])
    p.add_argument(
        "--shop-id", help="Only used to read this shop's active model config, never its messages"
    )
    p.add_argument("--core-url", default="http://127.0.0.1:8080")
    p.add_argument("--env-file", type=Path, default=ROOT / ".runtime/local.env")
    p.add_argument("--split", choices=["dev", "test", "all"], default="dev")
    p.add_argument(
        "--case", action="append", default=[], help="Select case IDs; repeat this option"
    )
    p.add_argument(
        "--scenario", action="append", default=[], help="Select scenario families; repeat this option"
    )
    p.add_argument("--one-per-scenario", action="store_true")
    p.add_argument("--limit", type=int)
    p.add_argument("--repeat", type=int, default=1)
    p.add_argument("--max-model-calls", type=int, default=100)
    p.add_argument("--retrieval", choices=["bm25", "hybrid"], default="bm25")
    p.add_argument(
        "--strategy",
        choices=["current", "react", "plan_execute"],
        default="current",
        help="Orchestration variant; data sources, tools and validation stay fixed.",
    )
    p.add_argument("--output", type=Path)
    p.add_argument("--compare", type=Path, help="Prior report.json with matching input hashes")
    p.add_argument("--reviews", type=Path, help="Manually labelled review-template.jsonl")
    p.add_argument(
        "--rescore", type=Path, help="Rescore an existing report without model or platform calls"
    )
    p.add_argument("--input-per-million", type=float)
    p.add_argument("--output-per-million", type=float)
    return p


def snapshot_sources(destination):
    """Copy code and fixtures; only a runtime link points to local DB/config files."""
    for relative in ["evals", "services/agent-service/copilot"]:
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(ROOT / relative, target, ignore=shutil.ignore_patterns("__pycache__"))
    scripts = destination / "scripts"
    scripts.mkdir()
    shutil.copy2(ROOT / "scripts/dev.py", scripts / "dev.py")
    (destination / ".runtime").symlink_to(ROOT / ".runtime", target_is_directory=True)


def source_contents():
    files = [
        p
        for base in [ROOT / "services/agent-service/copilot", ROOT / "evals"]
        for p in base.rglob("*")
        if p.is_file() and p.suffix in {".py", ".sql", ".jsonl"}
    ]
    files.append(ROOT / "scripts/dev.py")
    return {str(p.relative_to(ROOT)): p.read_text() for p in files}


def entry(args):
    if args.validate or args.rescore or os.getenv("COPILOT_EVAL_FROZEN_SOURCE") == "1":
        return asyncio.run(main(args))
    # A separate interpreter imports the copied Agent, so edits to the shared
    # workspace cannot change late imports halfway through an evaluation.
    with tempfile.TemporaryDirectory(prefix="pdd-eval-source-") as directory:
        frozen = Path(directory)
        snapshot_sources(frozen)
        dataset = frozen / "evals/agent_cases.jsonl"
        shutil.copyfile(args.dataset.resolve(), dataset)
        output = (
            args.output.resolve()
            if args.output
            else ROOT / ".runtime/evals" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        )
        command = [
            sys.executable,
            str(frozen / "evals/benchmark.py"),
            *sys.argv[1:],
            "--dataset",
            str(dataset),
            "--output",
            str(output),
            "--env-file",
            str(args.env_file.resolve()),
        ]
        process = subprocess.Popen(
            command, cwd=ROOT, env={**os.environ, "COPILOT_EVAL_FROZEN_SOURCE": "1"}
        )
        try:
            return process.wait()
        except BaseException:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            raise


async def main(args):
    os.umask(0o077)
    os.environ["LANGSMITH_TRACING"] = "false"
    os.environ["LANGCHAIN_TRACING_V2"] = "false"
    prices = (args.input_per_million, args.output_per_million)
    if any(p is not None for p in prices) and (
        any(p is None for p in prices) or any(not math.isfinite(p) or p < 0 for p in prices)
    ):
        raise ValueError("SUPPLY_BOTH_FINITE_NONNEGATIVE_PRICES")
    if args.rescore:
        if args.model_source or args.validate:
            raise ValueError("RESCORE_DOES_NOT_USE_MODEL_OR_VALIDATE")
        report = json.loads(args.rescore.read_text())
        reviews = (
            [json.loads(line) for line in args.reviews.read_text().splitlines() if line.strip()]
            if args.reviews
            else []
        )
        report["summary"] = summary(
            report["results"], reviews, args.input_per_million, args.output_per_million
        )
        report["metadata"]["rescored_at"] = datetime.now(timezone.utc).isoformat()
        if args.compare:
            report["comparison"] = compare(report, json.loads(args.compare.read_text()))
        output = args.output or ROOT / ".runtime/evals" / (
            "reviewed-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        )
        if output.exists():
            raise ValueError("OUTPUT_DIRECTORY_ALREADY_EXISTS")
        write_report(output, report)
        print(f"Report: {output / 'report.md'}; no model requests.")
        return 0
    cases = load(args.dataset)
    for case in cases:
        context_for(case)
    manifest = {
        "cases": len(cases),
        "scenarios": len({c.scenario for c in cases}),
        "dev": sum(c.split == "dev" for c in cases),
        "test": sum(c.split == "test" for c in cases),
        "data_source": "synthetic_only"
        if all(c.provenance.kind == "synthetic" for c in cases)
        else "curated_offline",
        "source_kinds": {
            kind: sum(c.provenance.kind == kind for c in cases)
            for kind in ["synthetic", "anonymized_real", "public_reference"]
        },
        "network_calls": 0,
    }
    if args.validate:
        print(json.dumps(manifest, ensure_ascii=False))
        return 0
    if not args.model_source:
        raise ValueError("CHOOSE_VALIDATE_OR_MODEL_SOURCE")
    if (
        not 1 <= args.repeat <= 10
        or args.max_model_calls < 1
        or (args.limit is not None and args.limit < 1)
    ):
        raise ValueError("INVALID_RUN_LIMITS")
    selected = [
        c
        for c in cases
        if (args.split == "all" or c.split == args.split)
        and (not args.case or c.id in args.case)
        and (not args.scenario or c.scenario in args.scenario)
    ]
    if args.case and set(args.case) != {c.id for c in selected}:
        raise ValueError("UNKNOWN_CASE_OR_WRONG_SPLIT")
    if args.scenario and set(args.scenario) != {c.scenario for c in selected}:
        raise ValueError("UNKNOWN_SCENARIO_OR_WRONG_SPLIT")
    if args.one_per_scenario:
        selected = list(
            {
                c.scenario: next(x for x in selected if x.scenario == c.scenario) for c in selected
            }.values()
        )
    selected = selected[: args.limit] if args.limit else selected
    if not selected:
        raise ValueError("NO_SELECTED_CASES")
    config = model_configuration(args)
    output = args.output or ROOT / ".runtime/evals" / datetime.now(timezone.utc).strftime(
        "%Y%m%dT%H%M%SZ"
    )
    if output.exists():
        raise ValueError("OUTPUT_DIRECTORY_ALREADY_EXISTS")
    output.mkdir(parents=True, mode=0o700)
    code = source_contents()
    if config["api_key"] in "\n".join(code.values()):
        raise ValueError("MODEL_KEY_EMBEDDED_IN_SOURCE")
    snapshot_path = output / "code-snapshot.json"
    with os.fdopen(
        os.open(snapshot_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w"
    ) as stream:
        json.dump(code, stream, ensure_ascii=False)
    report = {
        "metadata": {
            "dataset_hash": hashlib.sha256(args.dataset.read_bytes()).hexdigest(),
            "fixtures_hash": hashlib.sha256((ROOT / "evals/fixtures.py").read_bytes()).hexdigest(),
            "scorer_hash": hashlib.sha256((ROOT / "evals/scoring.py").read_bytes()).hexdigest(),
            "code_hash": fingerprint(code),
            "source_isolated": os.getenv("COPILOT_EVAL_FROZEN_SOURCE") == "1",
            "source_snapshot": "code-snapshot.json",
            "retrieval": args.retrieval,
            "strategy": args.strategy,
            "split": args.split,
            "model": config["model"],
            "base_url": config["base_url"],
            "max_rounds": config["max_rounds"],
            "timeout_seconds": config["timeout_seconds"],
            "planned_executions": len(selected) * args.repeat,
            "max_model_calls": args.max_model_calls,
            "data_source": manifest["data_source"],
            "source_kinds": {
                kind: sum(c.provenance.kind == kind for c in selected)
                for kind in manifest["source_kinds"]
            },
            "gold_visible_to_model": False,
            "created_at": datetime.now(timezone.utc).isoformat(),
            "status": "running",
        },
        "results": [],
    }
    budget = Budget(args.max_model_calls)
    with retrieval_database(args.retrieval):
        settings = Settings(
            token="isolated-evaluation-token-32-characters",
            shop_id="synthetic-shop",
            handoff_terms=(),
            llm_url=config["base_url"],
            llm_key=config["api_key"],
            llm_model=config["model"],
            llm_max_rounds=config["max_rounds"],
            agent_timeout=config["timeout_seconds"],
        )
        settings.evaluation_strategy = args.strategy
        settings.validate()
        for case in selected:
            for repeat in range(1, args.repeat + 1):
                if budget.used >= budget.maximum:
                    budget.exhausted = True
                    break
                row = await evaluate(case, settings, budget, repeat)
                report["results"].append(row)
                report["summary"] = summary(
                    report["results"], input_price=prices[0], output_price=prices[1]
                )
                write_report(output, report)
                print(
                    f"{case.id} repeat={repeat} auto={'pass' if row['score']['passed'] else 'fail'} calls={len(row['model_requests'])}",
                    flush=True,
                )
            if budget.exhausted:
                break
    reviews = (
        [json.loads(line) for line in args.reviews.read_text().splitlines() if line.strip()]
        if args.reviews
        else []
    )
    report["summary"] = summary(report["results"], reviews, *prices)
    report["metadata"]["status"] = "budget_exhausted" if budget.exhausted else "completed"
    after = source_contents()
    changed = sorted(
        path for path in code.keys() | after.keys() if code.get(path) != after.get(path)
    )
    report["metadata"]["changed_source_files"] = changed
    if changed and not budget.exhausted:
        report["metadata"]["status"] = "source_changed"
    if args.compare:
        report["comparison"] = compare(report, json.loads(args.compare.read_text()))
    write_report(output, report)
    print(f"Report: {output / 'report.md'}", flush=True)
    return (
        0
        if report["metadata"]["status"] == "completed"
        and all(r["score"]["passed"] for r in report["results"])
        else 1
    )


if __name__ == "__main__":
    try:
        raise SystemExit(entry(parser().parse_args()))
    except Exception as error:
        # Config/transport errors may carry credentials; only fixed uppercase codes
        # and exception types are safe to show. Reports contain synthetic data only.
        code = str(error)
        print(
            code if code.replace("_", "").isalnum() and code.isupper() else type(error).__name__,
            file=sys.stderr,
        )
        raise SystemExit(2) from None
