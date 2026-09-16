import hashlib
from types import SimpleNamespace
import pytest
from fastapi import HTTPException
from app.config import Settings
from app.recording_source import mark_complete, reclaim, disk_status


def test_only_completed_matching_sources_can_be_reclaimed(tmp_path):
    config = Settings(_env_file=None, freeswitch_recording_dir=str(tmp_path))
    source = tmp_path/'attempt.wav'
    source.write_bytes(b'RIFF-complete-recording')
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    size = source.stat().st_size
    with pytest.raises(HTTPException):
        reclaim(config, source.name, digest, size)
    assert source.exists()
    mark_complete(config, source.name)
    with pytest.raises(HTTPException):
        reclaim(config, source.name, '0'*64, size)
    assert source.exists()
    config.voice_recording_retention_sec = 3600
    with pytest.raises(HTTPException):
        reclaim(config, source.name, digest, size)
    config.voice_recording_retention_sec = 0
    assert reclaim(config, source.name, digest, size) == {'deleted':True}
    assert not source.exists()
    assert reclaim(config, source.name, digest, size) == {'deleted':True}


def test_disk_errors_and_low_space_stop_new_admission(tmp_path, monkeypatch):
    config = Settings(_env_file=None, freeswitch_recording_dir=str(tmp_path), voice_recording_reserve_bytes=100)
    monkeypatch.setattr('shutil.disk_usage', lambda _: SimpleNamespace(free=99))
    assert disk_status(config)['safe'] is False
    monkeypatch.setattr('shutil.disk_usage', lambda _: SimpleNamespace(free=101))
    assert disk_status(config)['safe'] is True
    def missing(_):
        raise OSError('unmounted')
    monkeypatch.setattr('shutil.disk_usage', missing)
    assert disk_status(config)['safe'] is False


def test_changed_and_symlink_sources_are_retained(tmp_path):
    config = Settings(_env_file=None, freeswitch_recording_dir=str(tmp_path))
    source = tmp_path/'attempt.wav'
    source.write_bytes(b'writing')
    mark_complete(config, source.name)
    source.write_bytes(b'changed after marker')
    with pytest.raises(HTTPException):
        reclaim(config, source.name, hashlib.sha256(source.read_bytes()).hexdigest(), source.stat().st_size)
    link = tmp_path/'link.wav'
    link.symlink_to(source)
    with pytest.raises(HTTPException):
        mark_complete(config, link.name)
    assert source.exists()
