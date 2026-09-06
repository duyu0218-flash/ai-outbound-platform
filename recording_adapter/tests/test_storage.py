import httpx
import pytest

from app.config import Settings
from app.models import RecordingDeleteRequest, RecordingIngestRequest
from app.storage import RecordingObjectStorage, RecordingSourceRejected, RecordingStorageError


class FakeS3:
    def __init__(self):
        self.bucket_exists = False
        self.uploads: dict[tuple[str, str], bytes] = {}
        self.deleted: list[tuple[str, str]] = []

    def head_bucket(self, *, Bucket):
        if not self.bucket_exists:
            from botocore.exceptions import ClientError

            raise ClientError({"Error": {"Code": "404", "Message": "missing"}}, "HeadBucket")

    def create_bucket(self, **kwargs):
        self.bucket_exists = True

    def upload_fileobj(self, fileobj, bucket, key, ExtraArgs):
        self.uploads[(bucket, key)] = fileobj.read()

    def delete_object(self, *, Bucket, Key):
        self.deleted.append((Bucket, Key))
        self.uploads.pop((Bucket, Key), None)


def _settings(**overrides):
    values = {
        "s3_access_key_id": "access",
        "s3_secret_access_key": "secret",
        "recording_source_allowed_hosts": "recordings.example.com,*.trusted.example",
        "recording_max_bytes": 1024,
    }
    values.update(overrides)
    return Settings(**values)


def test_ingest_uploads_and_returns_checksum():
    transport = httpx.MockTransport(
        lambda request: httpx.Response(200, headers={"content-type": "audio/wav"}, content=b"RIFF-recording")
    )
    fake_s3 = FakeS3()
    storage = RecordingObjectStorage(_settings(), s3_client=fake_s3, http_transport=transport)
    storage.ensure_bucket()
    result = storage.ingest(
        RecordingIngestRequest(
            recording_asset_id=7,
            tenant_id=2,
            call_id="call-123",
            provider_url="https://recordings.example.com/file.wav",
        )
    )
    assert result["storage_uri"] == "s3://ai-outbound-recordings/recordings/tenant-2/call-call-123/asset-7.wav"
    assert result["size_bytes"] == len(b"RIFF-recording")
    assert len(result["checksum_sha256"]) == 64
    assert next(iter(fake_s3.uploads.values())) == b"RIFF-recording"


def test_ingest_rejects_non_allowlisted_source():
    storage = RecordingObjectStorage(_settings(), s3_client=FakeS3())
    with pytest.raises(RecordingSourceRejected):
        storage.ingest(
            RecordingIngestRequest(
                recording_asset_id=1,
                tenant_id=1,
                call_id="call",
                provider_url="http://169.254.169.254/latest/meta-data",
            )
        )


def test_ingest_enforces_streaming_size_limit():
    transport = httpx.MockTransport(
        lambda request: httpx.Response(200, headers={"content-type": "application/octet-stream"}, content=b"x" * 2048)
    )
    storage = RecordingObjectStorage(_settings(), s3_client=FakeS3(), http_transport=transport)
    with pytest.raises(Exception, match="maximum size"):
        storage.ingest(
            RecordingIngestRequest(
                recording_asset_id=1,
                tenant_id=1,
                call_id="call",
                provider_url="https://recordings.example.com/file.raw",
            )
        )


def test_delete_is_tenant_scoped():
    fake_s3 = FakeS3()
    storage = RecordingObjectStorage(_settings(), s3_client=fake_s3)
    request = RecordingDeleteRequest(
        recording_asset_id=7,
        tenant_id=2,
        call_id="call-123",
        storage_uri="s3://ai-outbound-recordings/recordings/tenant-2/call-call-123/asset-7.wav",
    )
    assert storage.delete(request) is True
    assert fake_s3.deleted == [
        ("ai-outbound-recordings", "recordings/tenant-2/call-call-123/asset-7.wav")
    ]

    with pytest.raises(RecordingStorageError, match="tenant"):
        storage.delete(request.model_copy(update={"tenant_id": 3}))


def test_delete_does_not_claim_provider_side_deletion():
    storage = RecordingObjectStorage(_settings(), s3_client=FakeS3())
    request = RecordingDeleteRequest(
        recording_asset_id=7,
        tenant_id=2,
        call_id="call-123",
        provider_recording_id="provider-7",
        provider_url="https://recordings.example.com/file.wav",
    )

    with pytest.raises(RecordingStorageError, match="provider-side deletion is not supported"):
        storage.delete(request)


def test_runtime_rejects_relative_storage_prefix():
    with pytest.raises(RuntimeError, match="S3_KEY_PREFIX"):
        _settings(s3_key_prefix="recordings/../other").validate_runtime()


def test_ingest_spools_more_than_old_tmpfs_capacity(tmp_path):
    import hashlib
    size=65*1024*1024
    class Stream(httpx.SyncByteStream):
        def __iter__(self):
            for _ in range(65): yield b'x'*(1024*1024)
    class StreamingS3(FakeS3):
        def upload_fileobj(self,file,bucket,key,ExtraArgs):
            digest=hashlib.sha256();count=0
            while chunk:=file.read(1024*1024): digest.update(chunk);count+=len(chunk)
            self.size=count;self.digest=digest.hexdigest()
    s3=StreamingS3()
    storage=RecordingObjectStorage(_settings(recording_max_bytes=size,recording_spool_dir=str(tmp_path)),
        s3_client=s3,http_transport=httpx.MockTransport(lambda r:httpx.Response(200,headers={'content-type':'audio/wav'},stream=Stream())))
    result=storage.ingest(RecordingIngestRequest(recording_asset_id=9,tenant_id=1,call_id='large',provider_url='https://recordings.example.com/large.wav'))
    assert result['size_bytes']==s3.size==size and result['checksum_sha256']==s3.digest
    assert [p.name for p in tmp_path.iterdir()] == ['.lifecycle']


def test_recording_spool_full_rejects_before_download(tmp_path,monkeypatch):
    import shutil
    import app.storage as module
    monkeypatch.setattr(module.shutil,'disk_usage',lambda path:shutil._ntuple_diskusage(100,99,1))
    def unexpected(request): pytest.fail('must not download with insufficient disk space')
    storage=RecordingObjectStorage(_settings(recording_spool_dir=str(tmp_path)),s3_client=FakeS3(),http_transport=httpx.MockTransport(unexpected))
    with pytest.raises(RecordingStorageError,match='insufficient free space'):
        storage.ingest(RecordingIngestRequest(recording_asset_id=1,tenant_id=1,call_id='full',provider_url='https://recordings.example.com/a.wav'))


def test_delete_cannot_race_upload_and_tombstone_survives_restart(tmp_path):
    import threading
    from concurrent.futures import ThreadPoolExecutor
    entered=threading.Event();release=threading.Event()
    class SlowS3(FakeS3):
        def upload_fileobj(self,*args,**kwargs):
            entered.set();assert release.wait(5)
            return super().upload_fileobj(*args,**kwargs)
    s3=SlowS3();cfg=_settings(recording_spool_dir=str(tmp_path))
    transport=httpx.MockTransport(lambda r:httpx.Response(200,headers={'content-type':'audio/wav'},content=b'recording'))
    first=RecordingObjectStorage(cfg,s3_client=s3,http_transport=transport)
    second=RecordingObjectStorage(cfg,s3_client=s3,http_transport=transport)
    request=RecordingIngestRequest(recording_asset_id=1,tenant_id=1,call_id='race',provider_url='https://recordings.example.com/race.wav')
    deletion=RecordingDeleteRequest(recording_asset_id=1,tenant_id=1,call_id='race',storage_uri='s3://ai-outbound-recordings/recordings/tenant-1/call-race/asset-1.wav')
    with ThreadPoolExecutor(max_workers=1) as pool:
        pending=pool.submit(first.ingest,request);assert entered.wait(5)
        try:
            with pytest.raises(RecordingStorageError,match='in progress'): second.delete(deletion)
        finally: release.set()
        pending.result()
    assert second.delete(deletion)
    restarted=RecordingObjectStorage(cfg,s3_client=s3,http_transport=transport)
    with pytest.raises(RecordingStorageError,match='tombstone'): restarted.ingest(request)
    assert not s3.uploads
