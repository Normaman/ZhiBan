"""Versioned local dataset edits and result adjudications; no model calls."""

import hashlib
import types
import json
import os
import random
import re
import sys
import threading
import uuid
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path

from evals.case_data import context_for
from evals.dataset import Case, fingerprint, load, validate_cases
from evals.scoring import score, summary


def now():
    return datetime.now(timezone.utc).isoformat()


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    with temporary.open("w") as stream:
        os.chmod(temporary, 0o600)
        json.dump(value, stream, ensure_ascii=False, indent=2)
    temporary.replace(path)


def slug(value):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", value):
        raise ValueError("版本名仅支持字母、数字、下划线和短横线，最多80字")
    return value


class Conflict(ValueError):
    pass


class Workspace:
    def __init__(self, root, dataset, before, after):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.path = self.root / "workspace.json"
        self.lock = threading.RLock()
        self.dataset = Path(dataset).resolve()
        self.base = {c.id: c for c in load(self.dataset)}
        self.reports = {
            "before": json.loads(Path(before).read_text()),
            "after": json.loads(Path(after).read_text()),
        }
        self.report_hashes = {"before": sha(before), "after": sha(after)}
        self.legacy_storage_key = (
            "pdd-eval-review-"
            + hashlib.sha256(Path(before).read_bytes() + Path(after).read_bytes()).hexdigest()
        )
        for field in ["dataset_hash", "fixtures_hash", "scorer_hash", "retrieval"]:
            if (
                self.reports["before"]["metadata"][field]
                != self.reports["after"]["metadata"][field]
            ):
                raise ValueError("两侧报告使用的输入或评分口径不一致")
        self.fixtures_module = self._load_frozen_fixtures(Path(after), self.reports["after"])
        self.rows = {}
        for version, report in self.reports.items():
            if report["metadata"]["status"] != "completed":
                raise ValueError("只支持已完成的评测报告")
            if report["metadata"]["dataset_hash"] != sha(self.dataset):
                raise ValueError(
                    f"{version} 报告与基础数据集不一致：{self.dataset}。"
                    "请使用报告对应的原始数据集；新增用例需重新评测，不能与旧报告混用"
                )
            if report["metadata"]["fixtures_hash"] != hashlib.sha256(
                self._fixture_source.encode()
            ).hexdigest():
                raise ValueError("评测快照中的夹具与报告不匹配")
            if any(fingerprint(r["result"]) != r["result_fingerprint"] for r in report["results"]):
                raise ValueError("评测回答已被修改，指纹不匹配")
            self.rows[version] = {(r["case_id"], r["repeat"]): r for r in report["results"]}
            if len(self.rows[version]) != len(report["results"]):
                raise ValueError("报告有重复用例输出")
            for row in report["results"]:
                case = self.base.get(row["case_id"])
                if (
                    not case
                    or row["question"] != case.question
                    or row["expected"] != case.expected.model_dump()
                ):
                    raise ValueError("报告问题或标注与基础数据集不一致")
        if set(self.rows["before"]) != set(self.rows["after"]):
            raise ValueError("两侧报告用例未配对")
        rng = random.Random(20261109)
        self.sampled = set()
        for scenario in sorted({r["scenario"] for r in self.rows["after"].values()}):
            passed = [
                r
                for r in self.rows["after"].values()
                if r["scenario"] == scenario and r["score"]["passed"]
            ]
            if passed:
                row = rng.choice(passed)
                self.sampled.add((row["case_id"], row["repeat"]))
        if self.path.exists():
            self.state = json.loads(self.path.read_text())
            if (
                self.state["base_dataset_sha256"] != sha(self.dataset)
                or self.state["report_hashes"] != self.report_hashes
            ):
                raise ValueError("该工作区已绑定其他数据集或报告，请使用新工作区目录")
            records = [Case.model_validate(c) for c in self.state["cases"]]
            if len({c.id for c in records}) != len(records):
                raise ValueError("工作区有重复用例编号")
            validate_cases(self.active())
        else:
            self.state = {
                "schema_version": 1,
                "revision": 0,
                "base_dataset_sha256": sha(self.dataset),
                "report_hashes": self.report_hashes,
                "cases": [c.model_dump(mode="json") for c in self.base.values()],
                "archived": [],
                "journal": [],
                "adjudications": {},
                "reviews": {},
            }
            (self.root / "base-dataset.jsonl").write_bytes(self.dataset.read_bytes())
            (self.root / "base-dataset.jsonl").chmod(0o600)
            write_json(self.path, self.state)

    def _load_frozen_fixtures(self, report_path, report):
        metadata = report["metadata"]
        snapshot_path = report_path.with_name(metadata.get("source_snapshot", "code-snapshot.json"))
        try:
            snapshot = json.loads(snapshot_path.read_text())
            if fingerprint(snapshot) != metadata["code_hash"]:
                raise ValueError("源码快照指纹不匹配")
            source = snapshot["evals/fixtures.py"]
            if hashlib.sha256(source.encode()).hexdigest() != metadata["fixtures_hash"]:
                raise ValueError("夹具快照指纹不匹配")
            module = types.ModuleType("evals.frozen_fixtures_" + metadata["fixtures_hash"][:16])
            module.__file__ = str(snapshot_path) + ":evals/fixtures.py"
            from copilot.business_errors import BusinessToolError

            package = types.ModuleType("copilot.pdd")
            package.__path__ = []
            client = types.ModuleType("copilot.pdd.client")
            client.PlatformError = BusinessToolError
            previous_package = sys.modules.get("copilot.pdd")
            previous_client = sys.modules.get("copilot.pdd.client")
            sys.modules["copilot.pdd"] = package
            sys.modules["copilot.pdd.client"] = client
            try:
                exec(compile(source, module.__file__, "exec"), module.__dict__)
            finally:
                if previous_package is None:
                    sys.modules.pop("copilot.pdd", None)
                else:
                    sys.modules["copilot.pdd"] = previous_package
                if previous_client is None:
                    sys.modules.pop("copilot.pdd.client", None)
                else:
                    sys.modules["copilot.pdd.client"] = previous_client
        except (OSError, KeyError, TypeError, ValueError) as error:
            raise ValueError("找不到与评测报告匹配的原始夹具快照") from error
        self._fixture_source = source
        return module

    def context_for(self, case):
        return context_for(case, self.fixtures_module)

    def check_revision(self, revision):
        if type(revision) is not int or revision != self.state["revision"]:
            raise Conflict("工作区已更新，请刷新后重新检查，未覆盖其他页面的修改")

    def commit(self, state):
        state["revision"] += 1
        write_json(self.path, state)
        self.state = state

    def active(self, state=None):
        state = state or self.state
        return [Case.model_validate(c) for c in state["cases"] if c["id"] not in state["archived"]]

    def audit(self, state, kind, identity, reason, before, after):
        if not reason.strip():
            raise ValueError("请填写修改依据或原因")
        state["journal"].append(
            {
                "at": now(),
                "kind": kind,
                "id": identity,
                "reason": reason.strip(),
                "before": before,
                "after": after,
            }
        )

    def save_case(self, revision, value, reason, create=False):
        case = Case.model_validate(value)
        self.context_for(case)
        if case.provenance.kind != "synthetic" and not case.provenance.reference.strip():
            raise ValueError("真实脱敏或公开案例需填写资料来源，不能仅切换来源标识")
        with self.lock:
            self.check_revision(revision)
            state = deepcopy(self.state)
            existing = next((c for c in state["cases"] if c["id"] == case.id), None)
            if create and existing:
                raise Conflict("用例编号已存在，请换一个编号")
            data = case.model_dump(mode="json")
            if existing:
                state["cases"] = [data if c["id"] == case.id else c for c in state["cases"]]
            else:
                state["cases"].append(data)
            state["archived"] = [i for i in state["archived"] if i != case.id]
            validate_cases(self.active(state))
            self.audit(
                state,
                "case.create" if not existing else "case.edit",
                case.id,
                reason,
                existing,
                data,
            )
            self.commit(state)
            return self.state["revision"]

    def archive(self, revision, identity, reason):
        with self.lock:
            self.check_revision(revision)
            if identity not in {c["id"] for c in self.state["cases"]}:
                raise ValueError("用例不存在")
            state = deepcopy(self.state)
            if identity not in state["archived"]:
                state["archived"].append(identity)
            validate_cases(self.active(state))
            self.audit(state, "case.archive", identity, reason, False, True)
            self.commit(state)
            return self.state["revision"]

    def save_adjudication(
        self,
        revision,
        version,
        identity,
        repeat,
        result_hash,
        report_hash,
        overrides,
        reason,
        category,
    ):
        with self.lock:
            self.check_revision(revision)
            row = self.rows.get(version, {}).get((identity, repeat))
            if (
                not row
                or row["result_fingerprint"] != result_hash
                or self.report_hashes[version] != report_hash
            ):
                raise Conflict("判分校正绑定的原结果已不一致")
            known = {c["name"] for c in row["score"]["checks"]}
            if (
                not isinstance(overrides, dict)
                or not set(overrides) <= known
                or any(type(v) is not bool for v in overrides.values())
            ):
                raise ValueError("只能校正该结果已存在的检查项，判定需为通过或不通过")
            if category not in {
                "scorer_error",
                "label_error",
                "model_error",
                "data_gap",
                "uncertain",
            }:
                raise ValueError("请选择有效的问题分类")
            key = f"{version}:{identity}:{repeat}"
            record = {
                "case_id": identity,
                "repeat": repeat,
                "version": version,
                "result_fingerprint": result_hash,
                "report_sha256": report_hash,
                "check_overrides": overrides,
                "reason": reason.strip(),
                "category": category,
                "at": now(),
            }
            state = deepcopy(self.state)
            self.audit(
                state,
                "score.adjudicate",
                key,
                reason.strip() or "人工校正自动判分",
                state["adjudications"].get(key),
                record,
            )
            state["adjudications"][key] = record
            self.commit(state)
            return self.state["revision"]

    def save_review(self, revision, version, identity, repeat, result_hash, labels):
        with self.lock:
            self.check_revision(revision)
            row = self.rows.get(version, {}).get((identity, repeat))
            if not row or row["result_fingerprint"] != result_hash:
                raise Conflict("人工评分与原结果不匹配")
            fields = ["factually_correct", "grounded", "usable_without_edit"]
            if any(labels.get(k) is not None and type(labels.get(k)) is not bool for k in fields):
                raise ValueError("人工评分只能为是、否或未评")
            record = {
                "case_id": identity,
                "repeat": repeat,
                "result_fingerprint": result_hash,
                **{k: labels.get(k) for k in fields},
                "notes": str(labels.get("notes", ""))[:4000],
            }
            state = deepcopy(self.state)
            key = f"{version}:{identity}:{repeat}"
            self.audit(
                state, "score.review", key, "人工质量评分", state["reviews"].get(key), record
            )
            state["reviews"][key] = record
            self.commit(state)
            return self.state["revision"]

    def corrected_stats(self, version):
        rows = list(self.rows[version].values())
        passed, reviewed = 0, 0
        for row in rows:
            record = self.state["adjudications"].get(
                f"{version}:{row['case_id']}:{row['repeat']}", {}
            )
            overrides = record.get("check_overrides", {})
            reviewed += bool(record)
            passed += all(overrides.get(c["name"], c["passed"]) for c in row["score"]["checks"])
        return {
            "original_pass": sum(r["score"]["passed"] for r in rows),
            "corrected_pass": passed,
            "total": len(rows),
            "reviewed": reviewed,
        }

    def import_legacy_reviews(self, revision, ratings):
        if not isinstance(ratings, dict) or len(ratings) > 4000:
            raise ValueError("旧评分格式不正确")
        with self.lock:
            self.check_revision(revision)
            previous = deepcopy(self.state)
            imported = 0
            try:
                for key, labels in ratings.items():
                    if key in self.state["reviews"]:
                        continue
                    version, identity, repeat = key.split(":")
                    row = self.rows.get(version, {}).get((identity, int(repeat)))
                    if row is None or not isinstance(labels, dict):
                        raise ValueError("旧评分与当前报告不匹配")
                    # Validate and stage each record in memory; publish once below.
                    fields = ["factually_correct", "grounded", "usable_without_edit"]
                    if any(
                        labels.get(f) is not None and type(labels.get(f)) is not bool
                        for f in fields
                    ):
                        raise ValueError("旧评分只能为是、否或未评")
                    self.state["reviews"][key] = {
                        "case_id": identity,
                        "repeat": int(repeat),
                        "result_fingerprint": row["result_fingerprint"],
                        **{f: labels.get(f) for f in fields},
                        "notes": str(labels.get("notes", ""))[:4000],
                    }
                    imported += 1
                staged = deepcopy(self.state)
            finally:
                self.state = previous
            if imported:
                self.commit(staged)
            return {"revision": self.state["revision"], "imported": imported}

    def export(self, revision, version, reason):
        slug(version)
        if not reason.strip():
            raise ValueError("请填写该数据集版本的修订说明")
        with self.lock:
            self.check_revision(revision)
            cases = validate_cases(self.active())
            directory = self.root / "versions" / version
            directory.mkdir(parents=True, exist_ok=False, mode=0o700)
            dataset = directory / "cases.jsonl"
            dataset.write_text(
                "".join(
                    json.dumps(c.model_dump(mode="json"), ensure_ascii=False) + "\n" for c in cases
                )
            )
            dataset.chmod(0o600)
            manifest = {
                "format": "pdd-eval-dataset",
                "schema_version": 1,
                "version": version,
                "created_at": now(),
                "reason": reason,
                "workspace_revision": revision,
                "base_dataset_sha256": self.state["base_dataset_sha256"],
                "dataset_sha256": sha(dataset),
                "cases": len(cases),
                "dev": sum(c.split == "dev" for c in cases),
                "test": sum(c.split == "test" for c in cases),
                "source_kinds": {
                    k: sum(c.provenance.kind == k for c in cases)
                    for k in ["synthetic", "anonymized_real", "public_reference"]
                },
            }
            write_json(directory / "manifest.json", manifest)
            write_json(directory / "changes.json", self.state["journal"])
            write_json(directory / "adjudications.json", self.state["adjudications"])
            write_json(directory / "reviews.json", self.state["reviews"])
            return manifest

    def preview_import(self, values):
        if not isinstance(values, list) or len(values) > 2000:
            raise ValueError("导入必须为不超过2000条的用例列表")
        cases = validate_cases([Case.model_validate(v) for v in values])
        target = {c["id"]: c for c in self.state["cases"]}
        merged = {c.id: c for c in self.active()}
        for c in cases:
            if c.provenance.kind != "synthetic" and not c.provenance.reference.strip():
                raise ValueError("非合成案例需填写来源说明")
            self.context_for(c)
            merged[c.id] = c
        validate_cases(list(merged.values()))
        return {
            "new": sum(c.id not in target for c in cases),
            "updated": sum(
                c.id in target
                and (
                    c.id in self.state["archived"]
                    or fingerprint(target[c.id]) != fingerprint(c.model_dump(mode="json"))
                )
                for c in cases
            ),
            "unchanged": sum(
                c.id in target
                and c.id not in self.state["archived"]
                and fingerprint(target[c.id]) == fingerprint(c.model_dump(mode="json"))
                for c in cases
            ),
            "digest": fingerprint({"revision": self.state["revision"], "cases": values}),
        }

    def import_cases(self, revision, values, digest, reason):
        with self.lock:
            self.check_revision(revision)
            preview = self.preview_import(values)
            if preview["digest"] != digest:
                raise Conflict("导入预览已失效，请重新预览")
            state = deepcopy(self.state)
            for raw in values:
                c = Case.model_validate(raw).model_dump(mode="json")
                original = next((v for v in state["cases"] if v["id"] == c["id"]), None)
                if original == c and c["id"] not in state["archived"]:
                    continue
                state["cases"] = [v for v in state["cases"] if v["id"] != c["id"]] + [c]
                state["archived"] = [v for v in state["archived"] if v != c["id"]]
                self.audit(state, "case.import", c["id"], reason, original, c)
            if not reason.strip():
                raise ValueError("请填写导入原因")
            if state != self.state:
                self.commit(state)
            return self.state["revision"]

    def regrade(self, version):
        directory = self.root / "versions" / slug(version)
        if not (directory / "manifest.json").is_file():
            raise ValueError("数据集版本不存在，请先导出")
        manifest = json.loads((directory / "manifest.json").read_text())
        dataset = directory / "cases.jsonl"
        if sha(dataset) != manifest["dataset_sha256"]:
            raise ValueError("导出的数据集已被外部修改，请重新导出版本")
        cases = load(dataset)
        run_id = "regrade-" + uuid.uuid4().hex[:12]
        destination = self.root / "regrades" / run_id
        results = {}
        for name, source in self.reports.items():
            report = deepcopy(source)
            rows, pending = [], []
            for c in cases:
                original = self.base.get(c.id)
                if (
                    original is None
                    or fingerprint(self.context_for(c)) != fingerprint(self.context_for(original))
                    or c.input != original.input
                    or c.fixture != original.fixture
                ):
                    pending.append(
                        {
                            "case_id": c.id,
                            "reason": "新增用例或问题、上下文、测试事实变化，需要重新运行模型",
                        }
                    )
                    continue
                saved = [r for (identity, _), r in self.rows[name].items() if identity == c.id]
                if not saved:
                    pending.append({"case_id": c.id, "reason": "原报告没有该用例的输出"})
                    continue
                for old in saved:
                    row = deepcopy(old)
                    row["prior_score"] = row["score"]
                    row["score"] = score(c, row["result"], row["tools"], row["retrievals"])
                    for check in old["score"]["checks"]:
                        if check["name"] == "requested_hybrid_available":
                            row["score"]["checks"].append(check)
                            row["score"]["passed"] &= check["passed"]
                    row["expected"] = c.expected.model_dump()
                    row["split"] = c.split
                    row["scenario"] = c.scenario
                    rows.append(row)
            report["results"] = rows
            report.pop("comparison", None)
            report["summary"] = summary(rows)
            report["metadata"] = {
                **report["metadata"],
                "status": "requires_rerun" if pending else "completed",
                "dataset_hash": sha(dataset),
                "scorer_hash": sha(Path(__file__).with_name("scoring.py")),
                "regraded_at": now(),
                "regraded_from_sha256": self.report_hashes[name],
                "dataset_version": version,
                "additional_model_requests": 0,
                "pending_cases": pending,
                "planned_executions": len(rows),
            }
            write_json(destination / name / "report.json", report)
            results[name] = {
                "cached_rows": len(rows),
                "pending": pending,
                "passed": report["summary"]["auto_pass"],
                "status": report["metadata"]["status"],
            }
        write_json(
            destination / "manifest.json",
            {
                "run_id": run_id,
                "dataset_version": version,
                "at": now(),
                "additional_model_requests": 0,
                "reports": results,
            },
        )
        return {"run_id": run_id, "additional_model_requests": 0, "reports": results}
