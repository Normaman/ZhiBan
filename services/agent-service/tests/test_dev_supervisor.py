import json
from unittest.mock import Mock

import pytest

from scripts import dev


@pytest.fixture
def supervisor(tmp_path, monkeypatch):
    root = tmp_path / "project"
    root.mkdir()
    runtime = root / ".runtime"
    runtime.mkdir()
    proc = tmp_path / "proc"
    proc.mkdir()
    monkeypatch.setattr(dev, "ROOT", root)
    monkeypatch.setattr(dev, "RUNTIME", runtime)
    monkeypatch.setattr(dev, "PROC", proc)
    connection = Mock()
    connection.__enter__ = Mock(return_value=connection)
    connection.__exit__ = Mock(return_value=False)
    connection.connect_ex.return_value = 1
    monkeypatch.setattr(dev.socket, "socket", Mock(return_value=connection))
    return root, runtime, proc


def process(proc, pid, arguments, cwd):
    directory = proc / str(pid)
    directory.mkdir()
    (directory / "cmdline").write_bytes(b"\0".join(arg.encode() for arg in arguments))
    (directory / "cwd").symlink_to(cwd, target_is_directory=True)


def test_start_after_shutdown_removes_only_stale_process_record(supervisor):
    _, runtime, _ = supervisor
    record = runtime / "pids.json"
    record.write_text(json.dumps({"core": 2147305, "agent": 2147640, "web": 2147641}))
    state = runtime / "workspace.json"
    state.write_text('{"mode":"live"}')

    assert dev.prepare_start() is True
    assert not record.exists()
    assert state.read_text() == '{"mode":"live"}'


def test_reused_pid_is_not_stopped_or_treated_as_a_running_service(supervisor, monkeypatch):
    root, runtime, proc = supervisor
    (runtime / "pids.json").write_text(json.dumps({"agent": 123}))
    process(proc, 123, ["python", "unrelated_worker.py"], root)
    kill = Mock()
    monkeypatch.setattr(dev.os, "kill", kill)

    assert dev.running_services() == {}
    dev.stop()
    kill.assert_not_called()
    assert (proc / "123").exists()
    assert not (runtime / "pids.json").exists()


def test_same_agent_command_in_another_project_is_not_owned(supervisor):
    root, runtime, proc = supervisor
    other_project = root.parent / "other-project"
    other_project.mkdir()
    (runtime / "pids.json").write_text(json.dumps({"agent": 123}))
    process(proc, 123, ["python", "-m", "uvicorn", "copilot.app:app"], other_project)

    assert dev.running_services() == {}


def test_partial_start_requires_restart_and_preserves_live_record(supervisor):
    root, runtime, proc = supervisor
    record = runtime / "pids.json"
    record.write_text(json.dumps({"agent": 123, "web": 124}))
    process(proc, 123, ["python", "-m", "uvicorn", "copilot.app:app"], root)

    with pytest.raises(SystemExit, match="restart"):
        dev.prepare_start()
    assert record.exists()


def test_unknown_listener_prevents_launching_duplicate_services(supervisor):
    dev.socket.socket.return_value.connect_ex.return_value = 0

    with pytest.raises(SystemExit, match="8080"):
        dev.prepare_start()


def test_failed_start_cleans_up_partial_application_processes(supervisor, monkeypatch):
    monkeypatch.setattr(dev, "prepare_start", lambda: True)
    start = Mock(side_effect=RuntimeError("Core initialization failed"))
    stop = Mock()
    monkeypatch.setattr(dev, "start", start)
    monkeypatch.setattr(dev, "stop", stop)
    monkeypatch.setattr("sys.argv", ["dev.py", "start", "--no-build"])

    with pytest.raises(RuntimeError, match="Core initialization failed"):
        dev.main()
    stop.assert_called_once()
