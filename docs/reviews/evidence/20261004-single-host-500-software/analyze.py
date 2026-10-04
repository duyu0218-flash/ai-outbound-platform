"""Reproduce comparison.json from complete compressed synthetic run evidence."""
import gzip
import hashlib
import json
import tarfile
from functools import lru_cache
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent
INDEX = json.loads((ROOT / 'run-index.json').read_text())


def quantile(values, fraction=.99):
    values = sorted(values)
    return values[min(len(values)-1, int(len(values)*fraction))] if values else None


@lru_cache(maxsize=1)
def archived_files():
    with tarfile.open(ROOT/'raw-evidence.tar.gz','r:gz') as archive:
        return {member.name:archive.extractfile(member).read() for member in archive if member.isfile()}


def raw_bytes(path):
    return path.read_bytes() if path.exists() else archived_files()[str(path.relative_to(ROOT))]


def read_json(path):
    return json.loads(gzip.decompress(raw_bytes(path)))


def analyze(entry):
    prefix = ROOT / 'raw' / entry['label']
    report = read_json(Path(str(prefix) + '-results.json.gz'))
    samples = read_json(Path(str(prefix) + '-timing-samples.json.gz'))
    replies = samples['successful_reply_latencies_ms']
    assert len(replies) == report['committed_replies_observed']
    assert quantile(replies) == report['synthetic_reply_p99_ms']
    tasks = report['task_timeline']
    sequences = defaultdict(list)
    for task in tasks:
        sequences[task['call_id']].append(task['sequence'])
    counts, queued, executing = Counter(), defaultdict(list), defaultdict(list)
    claim_starts = 0
    lock_waits, pending_locks = [], defaultdict(list)
    events = [json.loads(line) for line in gzip.decompress(
        raw_bytes(Path(str(prefix) + '-ai-events.jsonl.gz'))).splitlines()]
    for event in sorted(events, key=lambda item:item['at']):
        if event['event'] == 'claim_started':
            claim_starts += 1
        elif event['event'] == 'pool':
            name = event['unit'];counts[name] += 1
            queued[name].append((event['executing']-event['queued'])*1000)
            executing[name].append((event['ended']-event['executing'])*1000)
        elif event['event'] == 'call_lock_wait':
            pending_locks[event['claim']].append(event['at'])
        elif event['event'] == 'call_lock_acquired' and pending_locks[event['claim']]:
            lock_waits.append((event['at']-pending_locks[event['claim']].pop(0))*1000)
    dispatch_names = {'load_action','_prepare_ai_turn','_finish_ai_turn','prepare',
        'record_speech','finish','complete','_load_and_prepare_ai_turn',
        '_finish_and_prepare_ai_action','_record_and_finish_speech'}
    final = entry['phase'].startswith('final_')
    injected = sum(agent.get('model_transport_errors_injected',0) for agent in report['production_agents'])
    if final:
        assert report['software_acceptance_passed'] and report['correctness_passed']
        assert len(tasks) == 1000 and len(sequences) == 500
        assert all(sorted(values) == [1,2] for values in sequences.values())
        assert report['reply_observer']['failure'] is None
    if entry['phase'] == 'final_fault':
        assert injected == 1 and report['agent_model_transport_retries'] >= 1
    result = {key:report[key] for key in ('software_acceptance_passed','correctness_passed',
        'capacity_slo_passed','load_validity_passed','conversation_control_slo_passed',
        'committed_replies_observed','conversation_errors','failed_metrics','ai_max_attempts',
        'task_states','model','synthetic_reply_p99_ms','reply_round_timings',
        'generation_duration_seconds','elapsed_with_drain_seconds','reply_observer',
        'model_transport_retries')}
    result.update(label=entry['label'],phase=entry['phase'],model_errors_injected=injected,
        unique_tasks=len(tasks),calls_with_task_sequences_1_2=sum(sorted(v)==[1,2] for v in sequences.values()),
        claim_attempts=claim_starts,normal_and_notice_db_dispatches=sum(counts[n] for n in dispatch_names),
        call_lock_wait_p99_ms=quantile(lock_waits),
        pool={n:dict(count=counts[n],queue_p99_ms=quantile(queued[n]),execute_p99_ms=quantile(executing[n])) for n in sorted(counts)},
        results_sha256=hashlib.sha256(raw_bytes(Path(str(prefix)+'-results.json.gz'))).hexdigest())
    return result, report


def main():
    results, reports = [], {}
    for entry in INDEX['runs']:
        summary, report = analyze(entry);results.append(summary);reports[entry['label']] = report
    finals = [item for item in results if item['phase'].startswith('final_')]
    assert len(finals) == 3
    final_source = reports[finals[0]['label']]['source_sha256']
    assert all(reports[item['label']]['source_sha256'] == final_source for item in finals)
    baseline = next(item for item in results if item['phase'] == 'comparison_baseline')
    fixture_names = INDEX['common_fixture_files']
    assert all(reports[baseline['label']]['source_sha256'][name] == final_source[name] for name in fixture_names)
    assert all(reports[item['label']]['topology'] == reports[baseline['label']]['topology'] for item in finals)
    improvements = {item['label']:(1-item['synthetic_reply_p99_ms']/baseline['synthetic_reply_p99_ms'])*100
                    for item in finals if item['phase'] != 'final_fault'}
    print(json.dumps(dict(software_delivery_passed=all(item['software_acceptance_passed'] for item in finals),
        real_500_call_capacity_verified=False,final_run_source_hashes_equal=True,
        comparison_fixture_hashes_equal=True,comparison_baseline=baseline['label'],
        reply_p99_improvement_percent=improvements,runs=results),indent=2,sort_keys=True))


if __name__ == '__main__':main()
