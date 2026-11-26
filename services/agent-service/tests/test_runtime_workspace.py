import json

from scripts import dev


def test_runtime_uses_one_workspace_and_keeps_platform_data_out_of_agent_state(
    tmp_path, monkeypatch
):
    directory = tmp_path / "platform-data"
    directory.mkdir()
    binding = {"platform_shop_id": "shop-verified", "account_id": "staff-verified"}
    (directory / "connection.json").write_text(json.dumps(binding))
    monkeypatch.setattr(dev, "RUNTIME", tmp_path / "runtime")

    env = dev.environment(directory)

    assert env["SHOP_ID"].startswith("pdd-")
    assert env["WORKSPACE_DATABASE"].startswith("copilot_live_")
    assert "AGENT_DATA_DIR" not in env
    assert env["PDD_DATA_DIR"] == str(directory)
    assert "COPILOT_MODE" not in env
    assert "CREDENTIAL_KEY" not in env and "CREDENTIAL_KEY_FILE" not in env
    assert env["LIVE_SEND_ENABLED"] == "false"


def test_unconfigured_install_starts_with_an_unbound_shop(tmp_path, monkeypatch):
    monkeypatch.setattr(dev, "RUNTIME", tmp_path / "runtime")

    env = dev.environment(tmp_path / "empty-platform-data")

    assert env["SHOP_ID"] == "initial-shop"
    assert env["WORKSPACE_DATABASE"] == "copilot"
    assert "COPILOT_MODE" not in env
    assert env["LIVE_SEND_ENABLED"] == "false"


def test_workspace_path_persists_without_mode_switching(tmp_path, monkeypatch):
    monkeypatch.setattr(dev, "RUNTIME", tmp_path)
    path = tmp_path / "workspace.json"
    path.write_text(json.dumps({"live_data_dir": str(tmp_path / "shop")}))
    assert dev.workspace() == (tmp_path / "shop").resolve()
