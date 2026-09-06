#!/usr/bin/env python3
"""Compare offline ASR exports against one immutable, human-labelled dataset.

Standard library only; never uploads audio, calls a model, or writes business data.
See docs/offline-asr-evaluation.md for the input contract and metric boundaries.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
import unicodedata
from pathlib import Path


NORMALIZATION = "nfkc-casefold-remove-whitespace-punctuation-v1"


def normalize(text: str) -> str:
    return "".join(
        char for char in unicodedata.normalize("NFKC", text).casefold()
        if not char.isspace() and not unicodedata.category(char).startswith("P")
    )


def distance(reference: str, hypothesis: str) -> int:
    # Linear memory Levenshtein; do not cap at reference length (CER may exceed 1).
    if len(reference) < len(hypothesis):
        reference, hypothesis = hypothesis, reference
    previous = list(range(len(hypothesis) + 1))
    for i, left in enumerate(reference, 1):
        current = [i]
        for j, right in enumerate(hypothesis, 1):
            current.append(min(current[-1] + 1, previous[j] + 1, previous[j - 1] + (left != right)))
        previous = current
    return previous[-1]


def number(value, name: str, *, positive: bool = False) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"{name} must be finite numeric data")
    if value < 0 or (positive and value == 0):
        raise ValueError(f"{name} is outside the permitted range")
    return float(value)


def required_text(value, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be a non-empty string")
    return value


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def unique_items(items, label: str) -> dict:
    if not isinstance(items, list) or not items:
        raise ValueError(f"{label} must be a non-empty list")
    result = {}
    for item in items:
        if not isinstance(item, dict):
            raise ValueError(f"{label} item must be an object")
        key = required_text(item.get("id"), f"{label}.id")
        if key in result:
            raise ValueError(f"duplicate {label} id: {key}")
        result[key] = item
    return result


def segments(item: dict, duration_ms: float) -> list[dict]:
    rows = item.get("segments")
    if not isinstance(rows, list):
        raise ValueError(f"{item['id']}: segments must be a list (empty is valid silence)")
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("text"), str):
            raise ValueError(f"{item['id']}: each segment needs text")
        start = number(row.get("start_ms"), "start_ms")
        end = number(row.get("end_ms"), "end_ms")
        if start > end or end > duration_ms:
            raise ValueError(f"{item['id']}: invalid segment interval")
        if row.get("speaker_role") is not None:
            required_text(row["speaker_role"], "speaker_role")
    return sorted(rows, key=lambda row: (row["start_ms"], row["end_ms"]))


def combined(rows: list[dict]) -> str:
    return normalize("".join(row["text"] for row in rows))


def ratio(numerator, denominator):
    return numerator / denominator if denominator else None


def percentile(values: list[float], quantile: float):
    return sorted(values)[math.ceil(len(values) * quantile) - 1] if values else None


def evaluate(manifest_path: Path, result_paths: list[Path], *, verify_audio: bool = False) -> dict:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict) or manifest.get("schema_version") != 1:
        raise ValueError("manifest schema_version must be 1")
    if manifest.get("dataset_kind") not in {"synthetic", "recorded"}:
        raise ValueError("dataset_kind must be synthetic or recorded")
    samples = unique_items(manifest.get("samples"), "samples")
    prepared = {}
    for key, sample in samples.items():
        if sample.get("authorized") is not True or sample.get("human_verified") is not True:
            raise ValueError(f"{key}: authorized and human_verified must both be true")
        sha = required_text(sample.get("audio_sha256"), "audio_sha256")
        if not re.fullmatch(r"[0-9a-f]{64}", sha):
            raise ValueError(f"{key}: invalid audio_sha256")
        duration = number(sample.get("duration_ms"), "duration_ms", positive=True)
        reference = segments(sample, duration)
        text = combined(reference)
        keywords = sample.get("keywords", [])
        if not isinstance(keywords, list):
            raise ValueError(f"{key}: keywords must be a list")
        keywords = {normalize(required_text(word, "keyword")) for word in keywords}
        if any(not word or word not in text for word in keywords):
            raise ValueError(f"{key}: every labelled keyword must occur in the reference")
        if verify_audio:
            path = manifest_path.parent / required_text(sample.get("audio_path"), "audio_path")
            if digest(path) != sha:
                raise ValueError(f"{key}: audio file SHA-256 mismatch")
        prepared[key] = (sample, duration, reference, text, keywords)

    dataset_sha = digest(manifest_path)
    providers = []
    identities = set()
    for result_path in result_paths:
        data = json.loads(result_path.read_text(encoding="utf-8"))
        if not isinstance(data, dict) or data.get("schema_version") != 1:
            raise ValueError("result schema_version must be 1")
        if data.get("dataset_sha256") != dataset_sha:
            raise ValueError("result dataset_sha256 does not match the manifest bytes")
        identity = tuple(required_text(data.get(field), field) for field in ("provider", "model", "revision"))
        if identity in identities:
            raise ValueError("duplicate provider/model/revision result")
        identities.add(identity)
        items = unique_items(data.get("items"), "results")
        if set(items) - set(samples):
            raise ValueError("results contain unknown sample ids")
        totals = {"edits": 0, "reference_chars": 0, "keyword_hits": 0, "keyword_total": 0}
        durations, costs, rtfs, details = [], [], [], []
        for key, (sample, duration, reference, ref_text, keywords) in prepared.items():
            item = items.get(key)
            state = "missing" if item is None else item.get("status")
            if state not in {"missing", "ok", "error"} or (item is not None and state == "missing"):
                raise ValueError(f"{key}: status must be ok or error")
            if item is not None and item.get("audio_sha256") != sample["audio_sha256"]:
                raise ValueError(f"{key}: result audio_sha256 mismatch")
            hypothesis = segments(item, duration) if state == "ok" else []
            hyp_text = combined(hypothesis)
            edits = distance(ref_text, hyp_text)
            hits = sum(word in hyp_text for word in keywords)
            totals["edits"] += edits
            totals["reference_chars"] += len(ref_text)
            totals["keyword_hits"] += hits
            totals["keyword_total"] += len(keywords)
            # Roles must be explicitly mapped by the export. Speaker 0 cannot
            # automatically be called the customer. This is role text CER, not DER.
            role_cer = None
            if reference and all(row.get("speaker_role") for row in reference) and all(row.get("speaker_role") for row in hypothesis):
                roles = {row["speaker_role"] for row in reference + hypothesis}
                role_edits = sum(distance(
                    combined([row for row in reference if row["speaker_role"] == role]),
                    combined([row for row in hypothesis if row["speaker_role"] == role]),
                ) for role in roles)
                role_cer = ratio(role_edits, len(ref_text))
            elapsed = None
            if state == "ok" and item.get("processing_ms") is not None:
                elapsed = number(item["processing_ms"], "processing_ms")
                durations.append(elapsed)
                rtfs.append(elapsed / duration)
            if item is not None and item.get("cost_cny") is not None:
                costs.append(number(item["cost_cny"], "cost_cny"))
            details.append({
                "id": key, "status": state, "edits": edits, "reference_chars": len(ref_text),
                "cer": ratio(edits, len(ref_text)), "role_text_cer": role_cer,
                "keyword_hits": hits, "keyword_total": len(keywords), "processing_ms": elapsed,
            })
        failed = sum(row["status"] != "ok" for row in details)
        providers.append({
            **dict(zip(("provider", "model", "revision"), identity)),
            "result_sha256": digest(result_path), "sample_count": len(samples),
            "failed_or_missing": failed, "complete": failed == 0, **totals,
            "cer": ratio(totals["edits"], totals["reference_chars"]),
            "keyword_recall": ratio(totals["keyword_hits"], totals["keyword_total"]),
            "processing_measured_count": len(durations),
            "offline_processing_p50_ms": percentile(durations, .50),
            "offline_processing_p95_ms": percentile(durations, .95),
            "offline_rtf_p95": percentile(rtfs, .95),
            "cost_measured_count": len(costs),
            "total_cost_cny": sum(costs) if len(costs) == len(samples) else None,
            "cost_per_audio_minute_cny": ratio(sum(costs), sum(value[1] for value in prepared.values()) / 60000) if len(costs) == len(samples) else None,
            "samples": details,
        })
    return {
        "schema_version": 1, "dataset_sha256": dataset_sha,
        "dataset_kind": manifest["dataset_kind"],
        "normalization": NORMALIZATION, "audio_files_verified": verify_audio,
        "real_line_verified": False, "production_approved": False,
        "providers": providers,
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--results", required=True, type=Path, nargs="+")
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--verify-audio", action="store_true")
    args = parser.parse_args(argv)
    try:
        report = evaluate(args.manifest, args.results, verify_audio=args.verify_audio)
        # Exclusive creation preserves original reports and input files.
        args.report.parent.mkdir(parents=True, exist_ok=True)
        with args.report.open("x", encoding="utf-8") as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2, allow_nan=False)
            stream.write("\n")
    except (OSError, ValueError, TypeError) as exc:
        print(f"evaluation failed: {exc}", file=sys.stderr)
        return 2
    print(f"report: {args.report.resolve()}")
    return 0 if all(item["complete"] for item in report["providers"]) else 1


if __name__ == "__main__":
    sys.exit(main())
