from __future__ import annotations

import hashlib
import mimetypes
import re
import shutil
import fcntl
import os
from contextlib import contextmanager
from pathlib import Path
from threading import BoundedSemaphore
from tempfile import SpooledTemporaryFile, TemporaryDirectory, NamedTemporaryFile
from typing import Any
from urllib.parse import unquote, urlparse

import boto3
from boto3.exceptions import S3UploadFailedError
from botocore.client import Config
from botocore.exceptions import BotoCoreError, ClientError
import httpx

from .config import Settings
from .models import RecordingDeleteRequest, RecordingIngestRequest


class RecordingStorageError(RuntimeError):
    pass


class RecordingSourceRejected(RecordingStorageError):
    pass


class RecordingDownloadError(RecordingStorageError):
    pass


def _safe_segment(value: object) -> str:
    normalized = re.sub(r"[^A-Za-z0-9._-]+", "-", str(value).strip()).strip("-.")
    if not normalized:
        raise RecordingStorageError("recording object key contains an empty segment")
    return normalized[:128]


class RecordingObjectStorage:
    def __del__(self):
        temporary = getattr(self, "_temporary_state", None)
        if temporary is not None:
            temporary.cleanup()

    def __init__(
        self,
        settings: Settings,
        *,
        s3_client: Any | None = None,
        http_transport: httpx.BaseTransport | None = None,
    ) -> None:
        self.settings = settings
        self._ingest_slots = BoundedSemaphore(max(1, settings.recording_ingest_concurrency))
        self._temporary_state = TemporaryDirectory(prefix="recording-state-") if not settings.recording_spool_dir else None
        self._state_dir = Path(settings.recording_spool_dir or self._temporary_state.name) / ".lifecycle"
        self._state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        self._http_transport = http_transport
        self.s3 = s3_client or boto3.client(
            "s3",
            endpoint_url=settings.s3_endpoint_url,
            region_name=settings.s3_region,
            aws_access_key_id=settings.s3_access_key_id,
            aws_secret_access_key=settings.resolved_s3_secret_access_key(),
            config=Config(signature_version="s3v4", s3={"addressing_style": "path"}),
        )

    def ensure_bucket(self) -> None:
        try:
            self.s3.head_bucket(Bucket=self.settings.s3_bucket)
            return
        except ClientError as exc:
            code = str(exc.response.get("Error", {}).get("Code", ""))
            if code not in {"404", "NoSuchBucket", "NotFound"} or not self.settings.s3_auto_create_bucket:
                raise RecordingStorageError(f"unable to access recording bucket: {code or exc}") from exc
        except BotoCoreError as exc:
            raise RecordingStorageError(f"unable to access recording bucket: {exc}") from exc
        try:
            kwargs: dict[str, Any] = {"Bucket": self.settings.s3_bucket}
            if self.settings.s3_region and self.settings.s3_region != "us-east-1":
                kwargs["CreateBucketConfiguration"] = {"LocationConstraint": self.settings.s3_region}
            self.s3.create_bucket(**kwargs)
        except ClientError as exc:
            code = str(exc.response.get("Error", {}).get("Code", ""))
            if code not in {"BucketAlreadyExists", "BucketAlreadyOwnedByYou"}:
                raise RecordingStorageError(f"unable to create recording bucket: {code or exc}") from exc
        except BotoCoreError as exc:
            raise RecordingStorageError(f"unable to create recording bucket: {exc}") from exc

    def ready(self) -> bool:
        try:
            self.s3.head_bucket(Bucket=self.settings.s3_bucket)
            return True
        except (BotoCoreError, ClientError):
            return False

    def _validate_source_url(self, source_url: str) -> None:
        parsed = urlparse(source_url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise RecordingSourceRejected("provider_url must use http or https")
        if parsed.username or parsed.password:
            raise RecordingSourceRejected("provider_url must not contain credentials")
        if self.settings.recording_source_require_https and parsed.scheme != "https":
            raise RecordingSourceRejected("provider_url must use https")
        hostname = parsed.hostname.lower().rstrip(".")
        allowed = False
        for pattern in self.settings.allowed_source_hosts():
            if pattern.startswith("*."):
                suffix = pattern[1:]
                allowed = hostname.endswith(suffix) and hostname != suffix[1:]
            elif hostname == pattern.rstrip("."):
                allowed = True
            if allowed:
                break
        if not allowed:
            raise RecordingSourceRejected("provider_url host is not allowlisted")

    @staticmethod
    def _extension(content_type: str, source_url: str) -> str:
        normalized_type = content_type.split(";", 1)[0].strip().lower()
        explicit = {
            "audio/wav": ".wav",
            "audio/x-wav": ".wav",
            "audio/mpeg": ".mp3",
            "audio/ogg": ".ogg",
            "audio/flac": ".flac",
            "audio/basic": ".ulaw",
            "application/octet-stream": ".raw",
        }.get(normalized_type)
        if explicit:
            return explicit
        guessed = mimetypes.guess_extension(normalized_type) if normalized_type else None
        if guessed:
            return guessed
        suffix = urlparse(source_url).path.rsplit("/", 1)[-1].rsplit(".", 1)
        if len(suffix) == 2 and re.fullmatch(r"[A-Za-z0-9]{1,8}", suffix[1]):
            return f".{suffix[1].lower()}"
        return ".bin"

    @contextmanager
    def _asset_operation(self, request):
        # Shared local volume + flock coordinates adapter workers, even when a
        # caller has timed out and the old HTTP handler is still uploading.
        identity = f"{request.tenant_id}:{request.call_id}:{request.recording_asset_id}"
        stem = self._state_dir / hashlib.sha256(identity.encode()).hexdigest()
        with Path(str(stem) + ".lock").open("a") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise RecordingStorageError("recording asset operation is in progress; retry later") from None
            try:
                yield stem
            finally:
                fcntl.flock(lock, fcntl.LOCK_UN)

    @staticmethod
    def _persist(path: Path, value: str):
        with NamedTemporaryFile(mode="w", dir=path.parent, delete=False) as file:
            temp = Path(file.name)
            try:
                file.write(value)
                file.flush()
                os.fsync(file.fileno())
                os.replace(temp, path)
                directory = os.open(path.parent, os.O_RDONLY)
                try:
                    os.fsync(directory)
                finally:
                    os.close(directory)
            finally:
                temp.unlink(missing_ok=True)

    def ingest(self, request: RecordingIngestRequest) -> dict[str, object]:
        if not self._ingest_slots.acquire(blocking=False):
            raise RecordingStorageError("recording ingestion capacity exhausted; retry later")
        try:
            if self.settings.recording_spool_dir:
                required = self.settings.recording_max_bytes * max(1, self.settings.recording_ingest_concurrency)
                if shutil.disk_usage(self.settings.recording_spool_dir).free < required + self.settings.recording_spool_reserve_bytes:
                    raise RecordingStorageError("recording spool has insufficient free space")
            with self._asset_operation(request) as stem:
                if Path(str(stem) + ".deleted").exists():
                    raise RecordingStorageError("recording asset has a deletion tombstone")
                return self._ingest(request, stem)
        finally:
            self._ingest_slots.release()

    def _ingest(self, request: RecordingIngestRequest, stem: Path) -> dict[str, object]:
        source_url = str(request.provider_url)
        self._validate_source_url(source_url)
        checksum = hashlib.sha256()
        size = 0
        content_type = "application/octet-stream"
        with httpx.Client(
            timeout=max(1, self.settings.recording_download_timeout_sec),
            follow_redirects=False,
            transport=self._http_transport,
        ) as client:
            try:
                with client.stream("GET", source_url, headers={"Accept": "audio/*, application/octet-stream"}) as response:
                    response.raise_for_status()
                    content_type = response.headers.get("content-type", content_type).split(";", 1)[0].strip().lower()
                    if not (content_type.startswith("audio/") or content_type == "application/octet-stream"):
                        raise RecordingDownloadError(f"unsupported recording content type: {content_type}")
                    declared_size = int(response.headers.get("content-length", "0") or 0)
                    if declared_size > self.settings.recording_max_bytes:
                        raise RecordingDownloadError("recording exceeds configured maximum size")
                    with SpooledTemporaryFile(max_size=8 * 1024 * 1024, mode="w+b", dir=self.settings.recording_spool_dir or None) as recording_file:
                        for chunk in response.iter_bytes(chunk_size=64 * 1024):
                            if not chunk:
                                continue
                            size += len(chunk)
                            if size > self.settings.recording_max_bytes:
                                raise RecordingDownloadError("recording exceeds configured maximum size")
                            checksum.update(chunk)
                            recording_file.write(chunk)
                        if size == 0:
                            raise RecordingDownloadError("recording download was empty")
                        extension = self._extension(content_type, source_url)
                        key = "/".join(
                            [
                                self.settings.s3_key_prefix.strip("/"),
                                f"tenant-{_safe_segment(request.tenant_id)}",
                                f"call-{_safe_segment(request.call_id)}",
                                f"asset-{_safe_segment(request.recording_asset_id)}{extension}",
                            ]
                        )
                        recording_file.seek(0)
                        key_path = Path(str(stem) + ".key")
                        if key_path.exists():
                            key = key_path.read_text()
                        else:
                            self._persist(key_path, key)
                        self.s3.upload_fileobj(
                            recording_file,
                            self.settings.s3_bucket,
                            key,
                            ExtraArgs={
                                "ContentType": content_type,
                                "Metadata": {
                                    "tenant-id": str(request.tenant_id),
                                    "call-id": request.call_id[:128],
                                    "recording-asset-id": str(request.recording_asset_id),
                                    "sha256": checksum.hexdigest(),
                                },
                            },
                        )
            except RecordingStorageError:
                raise
            except (httpx.HTTPError, ValueError) as exc:
                raise RecordingDownloadError(f"recording download failed: {exc}") from exc
            except (BotoCoreError, ClientError, S3UploadFailedError) as exc:
                raise RecordingStorageError(f"recording upload failed: {exc}") from exc
        return {
            "storage_uri": f"s3://{self.settings.s3_bucket}/{key}",
            "checksum_sha256": checksum.hexdigest(),
            "size_bytes": size,
        }

    def delete(self, request: RecordingDeleteRequest) -> bool:
        with self._asset_operation(request) as stem:
            # Tombstone survives process restart and blocks late/retried uploads.
            self._persist(Path(str(stem) + ".deleted"), "deleted")
            key_path = Path(str(stem) + ".key")
            if key_path.exists():
                managed = request.model_copy(update={"storage_uri": f"s3://{self.settings.s3_bucket}/{key_path.read_text()}"})
                self._delete(managed)
            return self._delete(request)

    def _delete(self, request: RecordingDeleteRequest) -> bool:
        if not request.storage_uri:
            if request.provider_url or request.provider_recording_id:
                raise RecordingStorageError(
                    "managed storage_uri is missing; provider-side deletion is not supported by this adapter"
                )
            return True
        parsed = urlparse(request.storage_uri)
        key = unquote(parsed.path.lstrip("/"))
        prefix = self.settings.s3_key_prefix.strip("/") + "/"
        if parsed.scheme != "s3" or parsed.netloc != self.settings.s3_bucket or not key.startswith(prefix):
            raise RecordingStorageError("storage_uri is outside the managed recording bucket or prefix")
        tenant_prefix = f"{prefix}tenant-{_safe_segment(request.tenant_id)}/"
        if not key.startswith(tenant_prefix):
            raise RecordingStorageError("storage_uri tenant does not match deletion request")
        try:
            self.s3.delete_object(Bucket=self.settings.s3_bucket, Key=key)
        except (BotoCoreError, ClientError) as exc:
            raise RecordingStorageError(f"recording deletion failed: {exc}") from exc
        return True
