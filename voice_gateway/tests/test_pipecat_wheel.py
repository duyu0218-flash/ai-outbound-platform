"""Verify provenance and deterministic repackaging, never download in tests."""

import base64
import csv
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import zipfile

import pytest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("build_pipecat", ROOT / "scripts/build-pipecat-wheel.py")
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)
MANIFEST = json.loads((ROOT / "vendor/pipecat/manifest.json").read_text())


def load_checker():
    spec = importlib.util.spec_from_file_location("check_pipecat", ROOT / "scripts/check-pipecat-distribution.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_distribution_guard_rejects_reintroduced_nltk(monkeypatch):
    checker = load_checker()
    monkeypatch.setattr(checker.importlib.util, "find_spec", lambda name: object())
    with pytest.raises(RuntimeError, match="NLTK is importable"):
        checker.main()


def test_distribution_guard_rejects_changed_wheel(tmp_path, monkeypatch):
    checker = load_checker()
    (tmp_path / "vendor/pipecat").mkdir(parents=True)
    (tmp_path / "vendor/pipecat/manifest.json").write_text(json.dumps(MANIFEST))
    (tmp_path / "artifacts/wheels").mkdir(parents=True)
    (tmp_path / f"artifacts/wheels/pipecat_ai-{MANIFEST['version']}-py3-none-any.whl").write_bytes(b"tampered")
    monkeypatch.setattr(checker, "ROOT", tmp_path)
    with pytest.raises(RuntimeError, match="wheel digest mismatch"):
        checker.main()


def test_public_upstream_version_cannot_satisfy_the_patch_pin():
    from packaging.specifiers import SpecifierSet
    requirement = SpecifierSet("==" + MANIFEST["version"])
    assert MANIFEST["upstream_version"] not in requirement
    assert MANIFEST["version"] in requirement


def test_audit_maps_verified_patch_to_upstream_without_dropping_other_packages(monkeypatch):
    from types import SimpleNamespace
    spec = importlib.util.spec_from_file_location("audit_environment", ROOT / "scripts/audit-python-environment.py")
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    distributions = [
        SimpleNamespace(metadata={"Name": "pipecat-ai"}, version=MANIFEST["version"]),
        SimpleNamespace(metadata={"Name": "unrecognized-third-party"}, version="1.2.3"),
    ]
    monkeypatch.setattr(audit.metadata, "distributions", lambda: distributions)
    calls = []
    monkeypatch.setattr(audit.subprocess, "run", lambda *args, **kwargs: calls.append((args, kwargs)))
    assert audit.requirements() == ["pipecat-ai==1.8.1", "unrecognized-third-party==1.2.3"]
    assert len(calls) == 1
    assert calls[0][0][0][-1].endswith("check-pipecat-distribution.py")
    assert calls[0][1]["check"] is True


def test_wrong_upstream_is_rejected_before_opening_archive():
    with pytest.raises(ValueError, match="SHA-256 mismatch"):
        builder.build(b"untrusted upstream", MANIFEST)


def test_wheel_is_reproducible_with_valid_record_and_preserved_license():
    upstream = (ROOT / "artifacts/upstream/pipecat_ai-1.8.1-py3-none-any.whl").read_bytes()
    first = builder.build(upstream, MANIFEST)
    assert first == builder.build(upstream, MANIFEST)
    assert hashlib.sha256(first).hexdigest() == MANIFEST["patched_sha256"]
    with zipfile.ZipFile(io.BytesIO(first)) as patched, zipfile.ZipFile(io.BytesIO(upstream)) as original:
        prefix = f"pipecat_ai-{MANIFEST['version']}.dist-info/"
        metadata = patched.read(prefix + "METADATA").decode()
        assert "Requires-Dist: nltk" not in metadata
        assert f"Version: {MANIFEST['version']}\n" in metadata
        changed = {"pipecat/utils/string.py", "pipecat/utils/prewarm.py", "pipecat/utils/text/simple_text_aggregator.py"}
        for name in original.namelist():
            if name.startswith("pipecat/") and name not in changed:
                assert patched.read(name) == original.read(name), name
            if name.endswith("/LICENSE"):
                assert patched.read(name.replace("1.8.1.dist-info", MANIFEST["version"] + ".dist-info")) == original.read(name)
        for name, digest, size in csv.reader(io.StringIO(patched.read(prefix + "RECORD").decode())):
            if name.endswith("/RECORD"):
                assert digest == size == ""
                continue
            contents = patched.read(name)
            actual = base64.urlsafe_b64encode(hashlib.sha256(contents).digest()).rstrip(b"=").decode()
            assert digest == "sha256=" + actual
            assert int(size) == len(contents)
