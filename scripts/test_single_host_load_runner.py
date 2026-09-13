import importlib.util
from pathlib import Path
import subprocess
import sys

import pytest

spec = importlib.util.spec_from_file_location('isolated_load_runner', Path(__file__).with_name('run-single-host-load.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


@pytest.fixture
def invocation(monkeypatch, tmp_path):
    monkeypatch.setattr(runner, 'ROOT', tmp_path)
    monkeypatch.setattr(sys, 'argv', ['run-single-host-load.py', '--label','single500-unit-test',
                                    '--image','synthetic.invalid/image'])
    return tmp_path


def test_existing_project_is_never_started_or_deleted(invocation, monkeypatch):
    monkeypatch.setattr(subprocess, 'check_output', lambda *a, **kw: 'existing-container\n')
    calls=[]
    monkeypatch.setattr(subprocess, 'run', lambda *a, **kw: calls.append(a))
    with pytest.raises(SystemExit) as caught: runner.main()
    assert caught.value.code == 2
    assert calls == []


def test_timed_out_load_cleans_only_its_fresh_project(invocation, monkeypatch):
    monkeypatch.setattr(subprocess, 'check_output', lambda *a, **kw: '')
    calls=[]
    def run(command, **kwargs):
        calls.append(command)
        if 'up' in command: raise subprocess.TimeoutExpired(command, 1)
        return subprocess.CompletedProcess(command, 0)
    monkeypatch.setattr(subprocess, 'run', run)
    with pytest.raises(subprocess.TimeoutExpired): runner.main()
    assert len(calls)==2 and calls[1][-3:]==['down','--volumes','--remove-orphans']
    assert all(command[3]=='single500-unit-test' for command in calls)
    assert (invocation/'artifacts/single-host-500/single500-unit-test-compose.log').exists()


def test_missing_report_cannot_exit_as_a_success(invocation, monkeypatch):
    monkeypatch.setattr(subprocess, 'check_output', lambda *a, **kw: '')
    monkeypatch.setattr(subprocess, 'run', lambda command, **kw: subprocess.CompletedProcess(command, 0))
    assert runner.main()==1
