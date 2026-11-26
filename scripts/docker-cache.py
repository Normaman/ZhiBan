"""Inspect or prune duplicate PDD build results without touching images or volumes."""

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[1]
# Generic npm/pip/base-image layers lack reliable ownership; leave them alone.
RECIPES = (
    "COPY services/core-service/src src",
    "mvn --settings maven-settings.xml -B -DskipTests package",
    "mvn --settings maven-settings.xml -B dependency:build-classpath",
    "/src/services/core-service/target/core-service-0.1.0.jar app.jar",
    "COPY --from=build --chown=10002:10002 /ms-playwright /ms-playwright",
    "pdd-copilot-agent",
    "requirements-rag-cpu.lock",
    "/opt/pdd-login/venv/bin/python -m playwright",
)


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True)


def records(builder, *filters):
    args = ["buildx", "du", "--builder", builder, "--format", "json"]
    for value in filters:
        args.extend(["--filter", value])
    return [json.loads(line) for line in docker(*args).splitlines() if line.strip()]


def select(rows, keep):
    groups = {}
    for row in rows:
        description = row.get("Description", "")
        if (row.get("Type") != "regular" or not row.get("Reclaimable")
                or not any(recipe in description for recipe in RECIPES)):
            continue
        groups.setdefault(description, []).append(row)
    selected = []
    for description, group in groups.items():
        # Timestamps from one builder use the same UTC representation.
        group.sort(key=lambda row: (row["CreatedAt"], row["ID"]), reverse=True)
        obsolete = any(marker in description for marker in (
            "--extra desktop", "--extra login",
            "/opt/pdd-login/venv/bin/python -m playwright",
        ))
        selected.extend(group if obsolete else group[keep:])
    return selected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", nargs="?", choices=("status", "plan", "clean"), default="status")
    parser.add_argument("--builder", default=os.environ.get("DOCKER_CACHE_BUILDER", "default"))
    parser.add_argument("--keep", type=int, default=2, help="Keep at least two results per exact recipe")
    args = parser.parse_args()
    if args.keep < 2:
        parser.error("--keep must be at least 2")
    if args.action == "status":
        print(docker("context", "show"), end="")
        print(docker("system", "df"), end="")
        return

    chosen = select(records(args.builder), args.keep)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    directory = ROOT / ".runtime" / "docker-maintenance" / stamp
    directory.mkdir(parents=True, exist_ok=False)
    plan = {"context": docker("context", "show").strip(), "builder": args.builder,
            "keep_per_recipe": args.keep,
            "reason": "Duplicate project-specific regular results or retired Python browser recipes. "
                      "Download mounts, unknown recipes, images, containers and volumes excluded.",
            "records": chosen}
    (directory / "plan.json").write_text(json.dumps(plan, ensure_ascii=False, indent=2))
    print(f"计划：{directory / 'plan.json'}；候选缓存 {len(chosen)} 条", flush=True)
    if args.action == "plan" or not chosen:
        return

    selector = "id~=^(" + "|".join(re.escape(row["ID"]) for row in chosen) + ")$"
    preview = records(args.builder, selector, "type=regular")
    allowed = {row["ID"] for row in chosen}
    if any(row["ID"] not in allowed or row["Type"] != "regular" for row in preview):
        raise RuntimeError("Cache selection changed; cleanup cancelled")
    # No force deletion or build-history API. Only the recorded IDs are eligible;
    # image references, cache dependencies and in-use records remain Docker-owned.
    command = ["docker", "buildx", "prune", "--builder", args.builder, "--all",
               "--filter", selector, "--filter", "type=regular"]
    result = subprocess.run(command, input="y\n", text=True, capture_output=True)
    (directory / "prune.log").write_text(result.stdout + result.stderr)
    print(result.stdout, end="")
    if result.returncode:
        raise RuntimeError(f"Docker cleanup failed; see {directory / 'prune.log'}")
    print(docker("system", "df"), end="")


if __name__ == "__main__":
    main()
