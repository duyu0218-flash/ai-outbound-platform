#!/usr/bin/env python3
"""Fail-closed evaluation of repeated six-case reports; never certifies real telephony."""
import argparse
import json
import math
from pathlib import Path

CASES = {'dialogue50': ('conversation', 50, 10), 'dialogue80': ('conversation', 80, 5),
         'dialogue100': ('conversation', 100, 5), 'dialogue125': ('conversation', 125, 5),
         'callback600': ('mixed', 200, 120), 'callback1200': ('mixed', 400, 30)}


def evaluate(manifest, base):
    results = []
    hashes = None
    seen = set()
    for name, (scenario, rate, length) in CASES.items():
        files = manifest.get('cases', {}).get(name, [])
        errors = []
        if len(files) < 5:
            errors.append('requires at least five independent reports')
        for relative in files:
            path = (base/relative).resolve()
            try:
                raw = path.read_bytes()
                import hashlib
                digest = hashlib.sha256(raw).hexdigest()
                if path in seen or digest in seen:
                    errors.append('duplicate report')
                seen.update((path, digest))
                data = json.loads(raw)
                source = data.get('source_sha256')
                if not source:
                    errors.append('missing source hashes')
                elif hashes is None:
                    hashes = source
                elif source != hashes:
                    errors.append('source revisions differ')
                if data.get('scenario') != scenario or data.get('initial_speech_start_rate') != rate:
                    errors.append('wrong scenario or offered rate')
                if data.get('final_transcripts_per_second') != rate:
                    errors.append('wrong sustained turn rate')
                if data.get('conversation_rounds' if scenario == 'conversation' else 'duration_seconds') != length:
                    errors.append('wrong duration or rounds')
                required = ['correctness_passed', 'capacity_slo_passed', 'runtime_source_unchanged_during_test']
                if scenario == 'conversation':
                    required += ['conversation_correctness_passed', 'conversation_control_slo_passed']
                    if data.get('synthetic_reply_p99_ms', float('inf')) > 4200:
                        errors.append('reply latency exceeded')
                    if data.get('deadline_miss_count', len(data.get('deadline_misses', []))) != 0:
                        errors.append('missed scheduled turns')
                if any(data.get(key) is not True for key in required):
                    errors.append('missing or failed correctness/SLO flag')
                measurements = [data.get('gateway_delivery_max_ms'), data.get('inbox_final', {}).get('max_completion_latency_ms')]
                if scenario == 'conversation':measurements.append(data.get('synthetic_reply_p99_ms'))
                if any(not isinstance(v,(int,float)) or isinstance(v,bool) or not math.isfinite(v) or v < 0 for v in measurements):
                    errors.append('invalid latency measurement')
                expected = 500*length if scenario == 'conversation' else rate*length
                if data.get('final_transcripts') != expected or data.get('inbox_final',{}).get('processed') != expected*3:
                    errors.append('offered load not completed')
                if data.get('gateway_delivery_max_ms', float('inf')) > 1000:
                    errors.append('delivery latency exceeded')
                inbox = data.get('inbox_final', {})
                if inbox.get('max_completion_latency_ms', float('inf')) > 1000:
                    errors.append('inbox latency exceeded')
                if inbox.get('pending') != 0 or inbox.get('dead') != 0 or data.get('pending_callbacks') != 0:
                    errors.append('unfinished events or dead letters')
            except (OSError, ValueError, TypeError, KeyError):
                errors.append('unreadable or invalid report')
        results.append(dict(case=name, runs=len(files), passed=not errors, errors=sorted(set(errors))))
    return dict(cases=results, repeated_synthetic_passed=all(r['passed'] for r in results),
                production_capacity_verified=False, real_media_verified=False,
                long_soak_verified=False, fault_recovery_verified=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    args = parser.parse_args()
    result = evaluate(json.loads(args.manifest.read_text()), args.manifest.parent)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result['repeated_synthetic_passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
