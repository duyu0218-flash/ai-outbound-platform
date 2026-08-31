from fastapi.testclient import TestClient

from app import main as app_main


class FakeStorage:
    def ensure_bucket(self):
        return None

    def ready(self):
        return True

    def ingest(self, payload):
        return {
            "storage_uri": f"s3://bucket/tenant-{payload.tenant_id}/asset-{payload.recording_asset_id}.wav",
            "checksum_sha256": "a" * 64,
            "size_bytes": 12,
        }

    def delete(self, payload):
        return True


def _configure_settings(monkeypatch):
    monkeypatch.setattr(app_main.settings, "s3_access_key_id", "access")
    monkeypatch.setattr(app_main.settings, "s3_secret_access_key", "secret")
    monkeypatch.setattr(app_main.settings, "s3_secret_access_key_file", "")
    monkeypatch.setattr(app_main.settings, "recording_source_allowed_hosts", "recordings.example.com")


def test_recording_endpoints_require_token(monkeypatch):
    _configure_settings(monkeypatch)
    monkeypatch.setattr(app_main.settings, "service_token", "service-token-for-tests")
    monkeypatch.setattr(app_main.settings, "service_token_file", "")
    monkeypatch.setattr(app_main, "storage", FakeStorage())
    with TestClient(app_main.app) as client:
        assert client.post(
            "/v1/recordings/ingest",
            json={
                "recording_asset_id": 1,
                "tenant_id": 1,
                "call_id": "call-1",
                "provider_url": "https://recordings.example.com/a.wav",
            },
        ).status_code == 401


def test_ingest_delete_ready_and_metrics(monkeypatch):
    _configure_settings(monkeypatch)
    monkeypatch.setattr(app_main.settings, "service_token", "service-token-for-tests")
    monkeypatch.setattr(app_main.settings, "service_token_file", "")
    monkeypatch.setattr(app_main.settings, "metrics_token", "metrics-token-for-tests")
    monkeypatch.setattr(app_main.settings, "metrics_token_file", "")
    monkeypatch.setattr(app_main, "storage", FakeStorage())
    headers = {"Authorization": "Bearer service-token-for-tests"}
    with TestClient(app_main.app) as client:
        assert client.get("/readyz").status_code == 200
        ingested = client.post(
            "/v1/recordings/ingest",
            headers=headers,
            json={
                "recording_asset_id": 1,
                "tenant_id": 1,
                "call_id": "call-1",
                "provider_url": "https://recordings.example.com/a.wav",
            },
        )
        assert ingested.status_code == 200
        assert ingested.json()["checksum_sha256"] == "a" * 64
        deleted = client.post(
            "/v1/recordings/delete",
            headers=headers,
            json={
                "recording_asset_id": 1,
                "tenant_id": 1,
                "call_id": "call-1",
                "storage_uri": ingested.json()["storage_uri"],
            },
        )
        assert deleted.json() == {"deleted": True}
        assert client.get("/metrics").status_code == 401
        metrics = client.get("/metrics", headers={"Authorization": "Bearer metrics-token-for-tests"})
        assert metrics.status_code == 200
        assert "ai_outbound_recording_adapter_ingest_total" in metrics.text


def test_metrics_accept_mounted_token_file(monkeypatch, tmp_path):
    _configure_settings(monkeypatch)
    token_file = tmp_path / "metrics-token"
    token_file.write_text("mounted-adapter-metrics-token\n", encoding="utf-8")
    monkeypatch.setattr(app_main.settings, "metrics_token", "")
    monkeypatch.setattr(app_main.settings, "metrics_token_file", str(token_file))
    monkeypatch.setattr(app_main, "storage", FakeStorage())

    with TestClient(app_main.app) as client:
        response = client.get(
            "/metrics",
            headers={"Authorization": "Bearer mounted-adapter-metrics-token"},
        )

    assert response.status_code == 200
