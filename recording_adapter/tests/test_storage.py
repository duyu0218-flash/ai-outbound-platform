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
