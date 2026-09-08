#!/usr/bin/env python3
"""Validate rendered Compose budgets without printing credentials or deploying."""
import argparse
import json
from pathlib import Path


def assess(config):
    services = config['services']
    errors = []
    api = [v for k, v in services.items() if k == 'control-api' or k.startswith('control-api-')]
    ai = [v for k, v in services.items() if k.startswith('ai-worker-')]
    agents = [v for k, v in services.items() if k.startswith('ai-agent-')]
    media = [v for k, v in services.items() if k.startswith('media-')]
    for label, rows, required in [('API', api, 6), ('AI', ai, 4), ('Agent', agents, 2), ('media', media, 12)]:
        if len(rows) != required:
            errors.append(f'{label}: expected {required} processes')
    gateway = services['voice-gateway']['environment']
    specs = json.loads(gateway['MEDIA_WORKERS_JSON'])
    if int(gateway['VOICE_MAX_CONCURRENT']) != 500 or int(gateway['PIPECAT_MAX_ACTIVE_SESSIONS']) != 600:
        errors.append('gateway must admit 500 calls with 600 media resource slots')
    if len(specs) != 12 or sum(row['capacity'] for row in specs) != 600:
        errors.append('media roster must have 12 workers and 600 slots')
    if {row['id'] for row in specs} != {v['environment']['MEDIA_WORKER_ID'] for v in media}:
        errors.append('media roster identities differ from processes')
    for spec in specs:
        env = services[spec['id']]['environment']
        if int(env['MEDIA_WORKER_CAPACITY']) != spec['capacity'] or env['PIPECAT_MEDIA_WS_BASE'] != spec['ws_base']:
            errors.append('media capacity/address mismatch: ' + spec['id'])
    db_connections = sum(int(row['environment']['DATABASE_POOL_SIZE']) + int(row['environment'].get('DATABASE_MAX_OVERFLOW', 0))
                         for row in api + ai + [services['task-worker']])
    if db_connections > 64:
        errors.append('application DB pool budget exceeds 64')
    ai_slots = sum(int(row['environment']['TASK_AI_CONCURRENCY']) for row in ai)
    if ai_slots != 640:
        errors.append('AI lane budget must be 640')
    for row in api:
        env = row['environment']
        if (int(env['REQUEST_ADMISSION_TOTAL_INFLIGHT']) > int(env['DATABASE_POOL_SIZE'])
                or int(env['REQUEST_ADMISSION_WEBHOOK_INFLIGHT']) >= int(env['REQUEST_ADMISSION_TOTAL_INFLIGHT'])):
            errors.append('API must fit its DB pool and reserve management capacity')
    quota_configs = {tuple(row['environment'].get(key) for key in
                          ('LLM_QUOTA_DB_PATH', 'LLM_QUOTA_SCOPE', 'LLM_QUOTA_RPM', 'LLM_QUOTA_TPM', 'LLM_QUOTA_RPS')) for row in agents}
    if len(quota_configs) != 1 or not all(next(iter(quota_configs), ())):
        errors.append('Agent account quotas must be present and identical')
    quota_sources = set()
    for row in agents:
        mounts = [v for v in row.get('volumes', []) if v.get('target') == '/var/lib/model-quota']
        if len(mounts) != 1 or not mounts[0].get('source', '').endswith('model_quota') or mounts[0].get('read_only'):
            errors.append('Agents must mount the shared account quota volume')
        else:
            quota_sources.add((mounts[0].get('type'), mounts[0]['source']))
    if len(quota_sources) != 1:
        errors.append('Agents must use the same writable account quota volume')
    if len([v for v in services.values() if v.get('environment', {}).get('SCHEDULER_ENABLED') == 'true']) != 1:
        errors.append('exactly one scheduler role required')
    cpu = sum(float(v.get('cpus', 0)) for v in services.values())
    memory = sum(int(v.get('mem_limit', 0)) for v in services.values()) / 1024**3
    if cpu > 32 or memory > 56:
        errors.append('candidate 32 vCPU / 64 GiB budget exceeded (reserve 8 GiB for host)')
    for name, row in services.items():
        if row.get('network_mode') != 'host':
            errors.append(f'{name}: single-host profile requires Linux host network')
        if name.startswith(('control-api', 'ai-agent', 'media-')) or name == 'voice-gateway':
            command = row.get('command', [])
            if '--host' not in command or command[command.index('--host')+1] != '127.0.0.1':
                errors.append(f'{name}: internal endpoint must bind loopback')
    return {'static_config_passed': not errors, 'customer_inflight_limit': 500,
            'media_slots': sum(row['capacity'] for row in specs), 'ai_slots': ai_slots,
            'application_db_connections': db_connections, 'cpu_limits_total': cpu,
            'memory_limits_gib': memory, 'blockers': errors,
            'real_500_call_capacity_verified': False, 'whole_host_high_availability': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compose-json', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = assess(json.loads(args.compose_json.read_text()))
    except (OSError, KeyError, TypeError, ValueError, IndexError):
        result = {'static_config_passed': False, 'blockers': ['invalid or incomplete rendered Compose config']}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result['static_config_passed'] else 1)
