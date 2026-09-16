import importlib.util
from pathlib import Path
import sys
import json
import pytest

spec=importlib.util.spec_from_file_location('soak',Path(__file__).with_name('run-single-host-soak.py'))
soak=importlib.util.module_from_spec(spec);spec.loader.exec_module(soak)


def manifest(argv):
    return dict(argv=argv, target_host='127.0.0.1', duration_seconds=1,
        images={name:'example.invalid/image@sha256:'+'a'*64 for name in
            ('backend','agent','voice_gateway','recording_adapter','freeswitch','postgres','redis')})


def test_rotating_logs_preserve_every_byte(tmp_path):
    log=soak.RotatingLog(tmp_path,max_bytes=17)
    content=bytes(range(128))
    log.write(content[:12]);log.write(content[12:]);log.close()
    paths=sorted(tmp_path.glob('workload-*.log'))
    assert b''.join(p.read_bytes() for p in paths)==content
    assert all(p.stat().st_size<=17 for p in paths)


def test_early_exit_does_not_pass_and_does_not_overwrite(tmp_path):
    target=tmp_path/'run'
    result=soak.supervise(manifest([sys.executable,'-c','print("synthetic early exit")']),target,smoke=True)
    assert not result['supervision_completed'] and not result['real_500_capacity_verified']
    assert json.loads((target/'journal.jsonl').read_text().splitlines()[-1])['event']=='finished'
    with pytest.raises(FileExistsError):
        soak.supervise(manifest([sys.executable,'-c','pass']),target,smoke=True)


def test_smoke_duration_terminates_only_its_workload_and_never_certifies_media(tmp_path):
    result=soak.supervise(manifest([sys.executable,'-c','import time; print("synthetic",flush=True); time.sleep(60)']),tmp_path/'run',smoke=True)
    assert result['supervision_completed'] and result['elapsed_seconds']<10
    assert not result['real_500_capacity_verified']


def test_qualification_refuses_short_or_same_host_and_mutable_images():
    data=manifest([sys.executable,'-c','pass'])
    with pytest.raises(ValueError):soak.validate(data)
    data['duration_seconds']=28800
    with pytest.raises(ValueError):soak.validate(data)
    data['target_host']='approved-load-target.invalid'
    assert soak.validate(data)==28800
    data['images']['postgres']='postgres:latest'
    with pytest.raises(ValueError):soak.validate(data)
