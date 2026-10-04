import importlib.util
from pathlib import Path
import subprocess
import sys
import json

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


@pytest.mark.parametrize('failed_gate', ['correctness_passed','capacity_slo_passed','load_validity_passed',None])
def test_zero_process_exit_still_requires_every_report_gate(invocation, monkeypatch, failed_gate):
    monkeypatch.setattr(subprocess, 'check_output', lambda *a, **kw: '')
    gates = dict(correctness_passed=True, capacity_slo_passed=True, load_validity_passed=True)
    if failed_gate:gates[failed_gate]=False
    def run(command, **kwargs):
        if 'up' in command:
            path = invocation/'docs/reviews/evidence/20261003-single-host-500/single500-unit-test-results.json'
            path.write_text(json.dumps(gates))
        return subprocess.CompletedProcess(command, 0)
    monkeypatch.setattr(subprocess,'run',run)
    assert runner.main() == (1 if failed_gate else 0)


@pytest.mark.parametrize('software_ok', [True, False])
def test_explicit_software_acceptance_keeps_capacity_failure_visible(invocation, monkeypatch, software_ok):
    monkeypatch.setattr(sys, 'argv', sys.argv+['--acceptance','software'])
    monkeypatch.setattr(subprocess, 'check_output', lambda *a, **kw: '')
    gates=dict(correctness_passed=software_ok,software_acceptance_passed=software_ok,
               capacity_slo_passed=False,load_validity_passed=False)
    def run(command, **kwargs):
        if 'up' in command:
            assert kwargs['env']['SINGLE500_ACCEPTANCE']=='software'
            path=invocation/'docs/reviews/evidence/20261003-single-host-500/single500-unit-test-results.json'
            path.write_text(json.dumps(gates))
        return subprocess.CompletedProcess(command,0)
    monkeypatch.setattr(subprocess,'run',run)
    assert runner.main()==(0 if software_ok else 1)
    assert gates['capacity_slo_passed'] is False
