import hashlib
import json
import stat
from argparse import Namespace

import pytest

from scripts import knowledge_transfer as transfer


def archive():
    snapshot = json.dumps(
        [
            {
                "source_id": "rule",
                "source_type": "shop_policy",
                "topics": ["faq"],
                "text": "已核实的答复",
                "document_name": "规则.md",
                "source_location": "第 2 段",
            }
        ],
        ensure_ascii=False,
    )
    return {
        "format": transfer.FORMAT,
        "schema_version": 1,
        "exported_at": "2026-11-09T00:00:00Z",
        "source": {
            "shop_id": "source",
            "shop_name": "开发店铺",
            "mode": "live",
            "platform_shop_id": "platform-fixture",
            "version_label": "kb-1",
        },
        "snapshot_json": snapshot,
        "snapshot_sha256": hashlib.sha256(snapshot.encode()).hexdigest(),
    }


def test_backup_roundtrip_retains_unicode_metadata_and_is_private(tmp_path):
    value = archive()
    path = transfer.save_archive(value, tmp_path / "知识备份.json")
    assert transfer.check_archive(json.loads(path.read_text())) == value
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    with pytest.raises(FileExistsError):
        transfer.save_archive(value, path)


def test_corrupted_backup_is_rejected_before_target_authentication():
    value = archive()
    value["snapshot_json"] += " "
    with pytest.raises(ValueError, match="校验"):
        transfer.check_archive(value)


def test_automatic_target_requires_a_unique_bound_matching_platform_shop():
    value = archive()
    matching = {
        "id": "target",
        "mode": "live",
        "binding_state": "bound",
        "platform_shop_id": "platform-fixture",
    }
    wrong = {**matching, "id": "wrong", "platform_shop_id": "other-platform"}
    unbound = {**matching, "id": "pending", "binding_state": "unbound"}
    assert transfer.choose_target([matching, wrong, unbound], value) == matching
    for candidates in ([wrong, unbound], [matching, {**matching, "id": "duplicate"}]):
        with pytest.raises(ValueError, match="唯一匹配"):
            transfer.choose_target(candidates, value)
    assert transfer.choose_target([matching, wrong], value, "wrong") == wrong


def test_source_identifier_validation_rejects_sql_metacharacters(tmp_path, monkeypatch):
    monkeypatch.setattr(transfer.dev, "environment", lambda: {"WORKSPACE_DATABASE": "copilot", "SHOP_ID": "fixture-shop"})
    assert transfer.source_scope() == ("copilot", "fixture-shop")
    with pytest.raises(ValueError):
        transfer.source_scope(database="copilot; DROP DATABASE copilot")
    with pytest.raises(ValueError):
        transfer.source_scope(shop="shop' OR TRUE --")


def test_remote_plain_http_cannot_receive_local_admin_credentials():
    with pytest.raises(ValueError, match="HTTPS"):
        transfer.Workbench("http://example.test", "admin", "fixture-password")


def test_idempotent_migration_does_not_submit_a_publication(tmp_path, monkeypatch):
    calls = []

    class Client:
        shop = ""

        def __init__(self, *_):
            pass

        def call(self, path, body=None):
            calls.append((path, body))
            if path == "/bootstrap":
                return {
                    "shops": [
                        {
                            "id": "target",
                            "mode": "live",
                            "binding_state": "bound",
                            "platform_shop_id": "platform-fixture",
                        }
                    ]
                }
            assert path.endswith("/preview")
            return {
                "target_shop_name": "目标店铺",
                "added": 0,
                "updated": 0,
                "skipped": 1,
                "can_import": True,
                "unchanged": True,
            }

    monkeypatch.setattr(transfer, "Workbench", Client)
    args = Namespace(
        target_env=str(tmp_path / "missing.env"),
        username=None,
        target_url="http://localhost:5178",
        target_shop=None,
        rules_only=False,
        conflict_policy="keep_target",
        yes=True,
    )
    monkeypatch.setattr(transfer.getpass, "getpass", lambda _: "fixture-password")
    result = transfer.migrate(archive(), args)
    assert result["unchanged"] is True
    assert len(calls) == 2
