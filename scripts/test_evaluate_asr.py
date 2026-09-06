import json

import pytest

from scripts.evaluate_asr import digest, distance, evaluate, main, normalize


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    return path


def segment(text, role="customer"):
    return {"text": text, "start_ms": 0, "end_ms": 1000, "speaker_role": role}


def fixture_files(tmp_path):
    audio = tmp_path / "synthetic-audio.bin"
    audio.write_bytes(b"synthetic fixture only; not speech")
    sha = digest(audio)
    samples = [
        {"id": "a", "audio_path": audio.name, "audio_sha256": sha, "duration_ms": 2000,
         "authorized": True, "human_verified": True, "keywords": ["预约"],
         "segments": [segment("我要预约")]},
        {"id": "b", "audio_path": audio.name, "audio_sha256": sha, "duration_ms": 2000,
         "authorized": True, "human_verified": True, "keywords": ["好"],
         "segments": [segment("好")]},
    ]
    manifest = write(tmp_path / "manifest.json", {"schema_version": 1, "dataset_kind": "synthetic", "samples": samples})
    data = {"schema_version": 1, "dataset_sha256": digest(manifest), "provider": "synthetic",
            "model": "fixture", "revision": "v1", "items": [
                {"id": row["id"], "audio_sha256": sha, "status": "ok", "segments": row["segments"],
                 "processing_ms": 100, "cost_cny": 0.01} for row in samples]}
    results = write(tmp_path / "results.json", data)
    return manifest, results, data


def test_normalization_and_known_edit_distance():
    assert normalize("ＡＢＣ， 预约！") == "abc预约"
    assert normalize("一百") != normalize("100")
    assert distance("kitten", "sitting") == 3
    assert distance("", "幻觉") == 2


def test_identical_transcripts_and_explicit_audio_verification(tmp_path):
    manifest, results, _ = fixture_files(tmp_path)
    report = evaluate(manifest, [results], verify_audio=True)
    row = report["providers"][0]
    assert row["cer"] == 0 and row["keyword_recall"] == 1
    assert row["cost_per_audio_minute_cny"] == pytest.approx(0.3)
    assert row["offline_rtf_p95"] == .05
    assert report["audio_files_verified"] is True
    assert report["dataset_kind"] == "synthetic"
    assert report["real_line_verified"] is False
    assert report["production_approved"] is False


def test_cer_is_weighted_by_reference_chars_not_mean_of_samples(tmp_path):
    manifest, results, data = fixture_files(tmp_path)
    data["items"][1]["segments"] = [segment("错")]
    write(results, data)
    row = evaluate(manifest, [results])["providers"][0]
    assert row["cer"] == .2  # 1 edit / 5 reference chars, not (0 + 1) / 2.
    assert row["keyword_recall"] == .5


def test_missing_samples_remain_in_denominator_and_unknown_cost_is_not_zero(tmp_path):
    manifest, results, data = fixture_files(tmp_path)
    data["items"].pop(0)
    write(results, data)
    row = evaluate(manifest, [results])["providers"][0]
    assert row["failed_or_missing"] == 1 and row["complete"] is False
    assert row["cer"] == .8 and row["keyword_recall"] == .5
    assert row["total_cost_cny"] is None
    assert row["cost_per_audio_minute_cny"] is None


def test_role_swap_is_visible_even_when_plain_transcript_is_correct(tmp_path):
    manifest, results, data = fixture_files(tmp_path)
    data["items"][0]["segments"][0]["speaker_role"] = "agent"
    write(results, data)
    sample = evaluate(manifest, [results])["providers"][0]["samples"][0]
    assert sample["cer"] == 0
    assert sample["role_text_cer"] == 2  # Four deleted customer + four inserted agent chars.


@pytest.mark.parametrize("case", ["duplicate", "unknown", "dataset", "audio", "nan", "interval", "status"])
def test_invalid_or_mismatched_exports_are_rejected(tmp_path, case):
    manifest, results, data = fixture_files(tmp_path)
    if case == "duplicate":
        data["items"].append(data["items"][0])
    elif case == "unknown":
        data["items"][0]["id"] = "unknown"
    elif case == "dataset":
        data["dataset_sha256"] = "0" * 64
    elif case == "audio":
        data["items"][0]["audio_sha256"] = "0" * 64
    elif case == "nan":
        data["items"][0]["processing_ms"] = float("nan")
    elif case == "interval":
        data["items"][0]["segments"][0]["end_ms"] = 3000
    else:
        data["items"][0]["status"] = "missing"
    write(results, data)
    with pytest.raises(ValueError):
        evaluate(manifest, [results])


def test_reference_requires_human_verification(tmp_path):
    manifest, results, _ = fixture_files(tmp_path)
    data = json.loads(manifest.read_text())
    data["samples"][0]["human_verified"] = False
    write(manifest, data)
    with pytest.raises(ValueError, match="human_verified"):
        evaluate(manifest, [results])


def test_changed_audio_fails_verification(tmp_path):
    manifest, results, _ = fixture_files(tmp_path)
    (tmp_path / "synthetic-audio.bin").write_bytes(b"changed")
    with pytest.raises(ValueError, match="audio file"):
        evaluate(manifest, [results], verify_audio=True)


def test_cli_saves_report_and_preserves_existing_files(tmp_path):
    manifest, results, _ = fixture_files(tmp_path)
    report = tmp_path / "report.json"
    args = ["--manifest", str(manifest), "--results", str(results), "--report", str(report)]
    assert main(args) == 0
    before = report.read_bytes()
    assert main(args) == 2
    assert report.read_bytes() == before
    original = manifest.read_bytes()
    assert main([*args[:-1], str(manifest)]) == 2
    assert manifest.read_bytes() == original


def test_silence_insertion_is_reported_without_dividing_by_zero(tmp_path):
    manifest, results, data = fixture_files(tmp_path)
    refs = json.loads(manifest.read_text())
    refs["samples"] = [dict(refs["samples"][0], segments=[], keywords=[])]
    write(manifest, refs)
    data["dataset_sha256"] = digest(manifest)
    data["items"] = data["items"][:1]
    write(results, data)
    row = evaluate(manifest, [results])["providers"][0]
    assert row["edits"] == 4 and row["cer"] is None
    assert row["keyword_recall"] is None


def test_error_and_missing_exports_make_cli_return_failure(tmp_path):
    manifest, results, data = fixture_files(tmp_path)
    data["items"][0]["status"] = "error"
    write(results, data)
    assert main(["--manifest", str(manifest), "--results", str(results),
                 "--report", str(tmp_path / "report.json")]) == 1
