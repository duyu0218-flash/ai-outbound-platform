"""Real SDK -> local OTLP receiver; no external Langfuse project or customer data."""
import base64
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest

from app import observability as tracing
from app.config import settings
from app.main import app


@pytest.mark.parametrize("sample_rate,status", [(0.0, 200), (1.0, 200), (1.0, 401)])
def test_real_http_export(monkeypatch, sample_rate, status):
    received = []
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            received.append((self.path, self.headers.get("Authorization"),
                             self.rfile.read(int(self.headers["Content-Length"]))))
            self.send_response(status)
            self.send_header("Content-Type", "application/x-protobuf")
            self.end_headers()
        def log_message(self, *_):
            pass
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    public_key = "pk-local-" + uuid4().hex
    monkeypatch.setattr(settings, "langfuse_enabled", True)
    monkeypatch.setattr(settings, "langfuse_public_key", public_key)
    monkeypatch.setattr(settings, "langfuse_secret_key", "sk-local")
    monkeypatch.setattr(settings, "langfuse_base_url", f"http://127.0.0.1:{server.server_port}")
    monkeypatch.setattr(settings, "langfuse_sample_rate", sample_rate)
    monkeypatch.setattr(settings, "service_token", "local-service")
    try:
        with TestClient(app) as client:
            assert client.post("/agent/start", headers={"Authorization": "Bearer local-service"},
                               json={"call_id": "private-local-call", "phone": "13800138000",
                                     "mode": "ai_only", "script": "PRIVATE SCRIPT"}).status_code == 200
            tracing._client.flush()
        if sample_rate == 0:
            assert received == []
        else:
            assert len(received) == 1
            path, auth, body = received[0]
            assert path == "/api/public/otel/v1/traces"
            assert auth == "Basic " + base64.b64encode(f"{public_key}:sk-local".encode()).decode()
            message = ExportTraceServiceRequest.FromString(body)
            spans = [s for resource in message.resource_spans for scope in resource.scope_spans for s in scope.spans]
            assert [s.name for s in spans] == ["agent.start"]
            assert spans[0].end_time_unix_nano >= spans[0].start_time_unix_nano
            for private in (b"13800138000", b"private-local-call", b"PRIVATE SCRIPT", b"sk-local"):
                assert private not in body
    finally:
        server.shutdown()
        server.server_close()
        thread.join(3)
