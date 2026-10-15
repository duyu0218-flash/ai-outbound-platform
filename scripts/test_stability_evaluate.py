import importlib.util
import json
from pathlib import Path

spec = importlib.util.spec_from_file_location('stability_evaluate', Path(__file__).with_name('stability-evaluate.py'))
evaluator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluator)


def reports(tmp_path):
    cases = {}
    for name, (scenario, rate, length) in evaluator.CASES.items():
        cases[name] = []
        total = 500*length if scenario == 'conversation' else rate*length
        for repeat in range(5):
            data = dict(run_id=f'{name}-{repeat}', source_sha256={'app.py':'a'*64}, scenario=scenario,
                        initial_speech_start_rate=rate, final_transcripts_per_second=rate, conversation_rounds=length, duration_seconds=length,
                        correctness_passed=True, capacity_slo_passed=True, runtime_source_unchanged_during_test=True,
                        conversation_correctness_passed=True, conversation_control_slo_passed=True,
                        synthetic_reply_p99_ms=4000, deadline_miss_count=0, gateway_delivery_max_ms=900,
                        final_transcripts=total, pending_callbacks=0,
                        inbox_final=dict(max_completion_latency_ms=900, pending=0, dead=0, processed=total*3))
            path=tmp_path/f'{name}-{repeat}.json';path.write_text(json.dumps(data));cases[name].append(path.name)
    return dict(cases=cases)


def test_five_repeats_still_cannot_certify_production(tmp_path):
    result=evaluator.evaluate(reports(tmp_path),tmp_path)
    assert result['repeated_synthetic_passed']
    assert not result['production_capacity_verified'] and not result['long_soak_verified']


def test_missing_duplicate_and_changed_sources_fail_closed(tmp_path):
    manifest=reports(tmp_path)
    manifest['cases']['dialogue50'] = manifest['cases']['dialogue50'][:4]
    manifest['cases']['dialogue80'][1] = manifest['cases']['dialogue80'][0]
    path=tmp_path/manifest['cases']['dialogue100'][0]
    data=json.loads(path.read_text());data['source_sha256']={'app.py':'b'*64};path.write_text(json.dumps(data))
    result=evaluator.evaluate(manifest,tmp_path)
    assert not result['repeated_synthetic_passed']
    assert all(not row['passed'] for row in result['cases'][:3])


def test_invalid_latency_or_underload_cannot_pass(tmp_path):
    manifest=reports(tmp_path)
    for name, changes in [('dialogue50',{'synthetic_reply_p99_ms':float('nan')}),
                          ('dialogue80',{'deadline_miss_count':1}),
                          ('dialogue100',{'final_transcripts':1})]:
        path=tmp_path/manifest['cases'][name][0]
        data=json.loads(path.read_text());data.update(changes);path.write_text(json.dumps(data))
    result=evaluator.evaluate(manifest,tmp_path)
    assert all(not row['passed'] for row in result['cases'][:3])


def test_matching_ramp_but_wrong_sustained_rate_fails(tmp_path):
    manifest=reports(tmp_path)
    path=tmp_path/manifest['cases']['dialogue50'][0]
    data=json.loads(path.read_text());data['final_transcripts_per_second']=80;path.write_text(json.dumps(data))
    result=evaluator.evaluate(manifest,tmp_path)
    assert 'wrong sustained turn rate' in result['cases'][0]['errors']
