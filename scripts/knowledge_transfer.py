"""Export WSL knowledge without starting the Agent, or migrate it to an authorized workbench."""

import argparse
import getpass
import hashlib
import http.cookiejar
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

if __package__:
    from . import dev
else:
    import dev

FORMAT = "pdd-copilot-knowledge"
MAX_BYTES = 20 * 1024 * 1024


def check_archive(value):
    if (
        not isinstance(value, dict)
        or value.get("format") != FORMAT
        or value.get("schema_version") != 1
        or not isinstance(value.get("source"), dict)
        or not isinstance(value.get("snapshot_json"), str)
        or hashlib.sha256(value["snapshot_json"].encode()).hexdigest()
        != value.get("snapshot_sha256")
    ):
        raise ValueError("知识备份格式或校验值不正确，请重新导出。")
    entries = json.loads(value["snapshot_json"])
    if not isinstance(entries, list) or len(entries) > 500:
        raise ValueError("知识备份必须包含不超过 500 条资料。")
    return value


def source_scope(shop=None, database=None):
    env = dev.environment()
    default_database, default_shop = env["WORKSPACE_DATABASE"], env["SHOP_ID"]
    database, shop = database or default_database, shop or default_shop
    if not re.fullmatch(r"[a-z0-9_]{1,100}", database) or not re.fullmatch(
        r"[A-Za-z0-9_-]{1,100}", shop
    ):
        raise ValueError("开发数据库或店铺编号格式不正确。")
    return database, shop


def ensure_postgres():
    directory = dev.RUNTIME / "postgres"
    if not (directory / "PG_VERSION").is_file():
        raise RuntimeError("未找到 WSL 开发数据库 .runtime/postgres。")
    pg_ctl = dev.postgres_binary("pg_ctl")
    state = subprocess.run([pg_ctl, "-D", str(directory), "status"], capture_output=True)
    if state.returncode:
        result = subprocess.run(
            [
                pg_ctl,
                "-D",
                str(directory),
                "-l",
                str(dev.RUNTIME / "postgres.log"),
                "-o",
                f"-p {dev.PG_PORT} -h 127.0.0.1 -k {dev.RUNTIME}",
                "start",
            ],
            capture_output=True,
        )
        if result.returncode:
            raise RuntimeError("开发 PostgreSQL 未能启动，请检查 .runtime/postgres.log。")


def export_local(shop=None, database=None):
    database, shop = source_scope(shop, database)
    ensure_postgres()
    sql = """BEGIN READ ONLY;
SELECT json_build_object(
 'source', json_build_object('shop_id',s.id,'shop_name',s.name,'mode',s.mode,
   'platform_shop_id',CASE WHEN s.mode='live' AND s.binding_state='bound'
     THEN s.platform_shop_id ELSE NULL END,'version_label',k.version_label),
 'snapshot_json',k.snapshot)
FROM shops s JOIN knowledge_versions k ON k.id=s.knowledge_version AND k.shop_id=s.id
WHERE s.id=:'source_shop';
COMMIT;
"""
    result = subprocess.run(
        [
            dev.postgres_binary("psql"),
            "-X",
            "-qAt",
            "-h",
            str(dev.RUNTIME),
            "-p",
            str(dev.PG_PORT),
            "-d",
            database,
            "-v",
            "ON_ERROR_STOP=1",
            "-v",
            "source_shop=" + shop,
        ],
        input=sql,
        text=True,
        capture_output=True,
    )
    if result.returncode:
        raise RuntimeError("无法读取指定开发知识库，请核对源数据库和店铺编号。")
    if not result.stdout.strip():
        raise RuntimeError("这家开发店铺还没有生效的知识版本。")
    data = json.loads(result.stdout)
    return check_archive(
        {
            "format": FORMAT,
            "schema_version": 1,
            "exported_at": datetime.now(timezone.utc).isoformat(),
            "source": data["source"],
            "snapshot_json": data["snapshot_json"],
            "snapshot_sha256": hashlib.sha256(data["snapshot_json"].encode()).hexdigest(),
        }
    )


def save_archive(archive, destination=None):
    if destination is None:
        directory = dev.RUNTIME / "exports"
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        destination = directory / f"knowledge-{stamp}-{uuid.uuid4().hex[:6]}.json"
    destination = Path(destination).expanduser().resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(archive, ensure_ascii=False, indent=2) + "\n"
    if len(payload.encode()) > MAX_BYTES:
        raise ValueError("知识备份超过 20MB，请先整理资料。")
    # Never overwrite an earlier backup or expose shop rules through a world-readable file.
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write(payload)
    return destination


class Workbench:
    def __init__(self, url, username, password):
        parsed = urllib.parse.urlsplit(url)
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("目标地址只能填写工作台基础地址。")
        if parsed.scheme != "https" and not (
            parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1", "::1"}
        ):
            raise ValueError("远程工作台需要 HTTPS，本机可使用 HTTP。")
        self.url = url.rstrip("/") + "/api/v1"
        self.http = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
        )
        self.csrf = ""
        self.csrf_header = "X-CSRF-TOKEN"
        self.shop = ""
        self.refresh_csrf()
        self.call("/auth/login", {"username": username, "password": password})
        self.refresh_csrf()

    def refresh_csrf(self):
        state = self.call("/auth/session")
        self.csrf, self.csrf_header = state["csrf_token"], state["csrf_header"]

    def call(self, path, body=None):
        headers = {"X-Shop-Id": self.shop} if self.shop else {}
        if body is not None:
            headers.update({"Content-Type": "application/json", self.csrf_header: self.csrf})
        request = urllib.request.Request(
            self.url + path,
            data=None if body is None else json.dumps(body, ensure_ascii=False).encode(),
            headers=headers,
        )
        try:
            with self.http.open(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            try:
                code = json.load(error).get("code", "HTTP_" + str(error.code))
            except (ValueError, AttributeError):
                code = "HTTP_" + str(error.code)
            raise RuntimeError("工作台请求失败：" + str(code)) from None


def choose_target(shops, archive, requested=None):
    if requested:
        matches = [shop for shop in shops if shop["id"] == requested]
    else:
        platform = archive["source"].get("platform_shop_id")
        matches = [
            shop
            for shop in shops
            if platform
            and shop.get("mode") == "live"
            and shop.get("binding_state") == "bound"
            and shop.get("platform_shop_id") == platform
        ]
    if len(matches) != 1:
        raise ValueError(
            "无法唯一匹配目标店铺，请先完成同一拼多多店铺授权，或用 --target-shop 明确指定编号。"
        )
    return matches[0]


def migrate(archive, args):
    settings = {}
    env_path = Path(args.target_env)
    if env_path.is_file():
        settings = dict(
            line.split("=", 1)
            for line in env_path.read_text().splitlines()
            if "=" in line and not line.lstrip().startswith("#")
        )
    username = args.username or settings.get("REVIEWER_USER", "reviewer")
    password = settings.get("REVIEWER_PASSWORD") if not args.username else None
    if not password:
        password = getpass.getpass("目标工作台管理员密码：")
    target_url = args.target_url or "http://127.0.0.1:" + settings.get("WEB_PORT", "5173")
    client = Workbench(target_url, username, password)
    state = client.call("/bootstrap")
    target = choose_target(state["shops"], archive, args.target_shop)
    client.shop = target["id"]
    body = {
        "archive": archive,
        "include_products": not args.rules_only,
        "conflict_policy": args.conflict_policy,
        "base_version": "",
    }
    path = f"/admin/shops/{target['id']}/knowledge/migration"
    plan = client.call(path + "/preview", body)
    print(
        f"目标店铺：{plan['target_shop_name']}；新增 {plan['added']}，更新 {plan['updated']}，跳过 {plan['skipped']}。"
    )
    if not plan["can_import"]:
        if plan["missing_goods_ids"]:
            raise RuntimeError(
                "目标店铺缺少对应商品，请先同步商品，或明确使用 --rules-only 仅迁移店铺资料。"
            )
        raise RuntimeError("合并后超过 500 条资料，请先整理目标知识库。")
    if plan["unchanged"]:
        print("资料已存在，无需迁移；未创建重复版本。")
        return plan
    if not args.yes and input("确认将这些资料发布到上述店铺？[y/N] ").strip().lower() != "y":
        print("已取消，备份仍保留。")
        return None
    body["base_version"] = plan["base_version"]
    result = client.call(path, body)
    print("知识迁移已完成；已有草稿需要重新生成。")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    for name in ("export", "migrate"):
        command = commands.add_parser(name)
        command.add_argument("--source-shop")
        command.add_argument("--source-db")
        command.add_argument("--output", type=Path)
        if name == "migrate":
            command.add_argument("--archive", type=Path)
            command.add_argument(
                "--target-url", help="Defaults to the Docker workbench WEB_PORT in infra/.env"
            )
            command.add_argument("--target-shop")
            command.add_argument("--target-env", default=str(dev.ROOT / "infra/.env"))
            command.add_argument("--username")
            command.add_argument(
                "--conflict-policy",
                choices=["keep_target", "replace_matches"],
                default="keep_target",
            )
            command.add_argument("--rules-only", action="store_true")
            command.add_argument("--yes", action="store_true")
    args = parser.parse_args()
    try:
        if getattr(args, "archive", None):
            if args.archive.stat().st_size > MAX_BYTES:
                raise ValueError("知识备份不能超过 20MB。")
            archive = check_archive(json.loads(args.archive.read_text(encoding="utf-8-sig")))
        else:
            archive = export_local(args.source_shop, args.source_db)
            path = save_archive(archive, args.output)
            print(f"已导出 {len(json.loads(archive['snapshot_json']))} 条资料：{path}")
        if args.action == "migrate":
            migrate(archive, args)
    except (ValueError, RuntimeError, OSError, urllib.error.URLError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
