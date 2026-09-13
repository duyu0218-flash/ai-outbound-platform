#!/usr/bin/env python3
"""Run one isolated synthetic qualification and remove its own temporary stack.

No carrier/cloud requests. Reports and logs survive cleanup. A nonzero result
must not be described as a capacity pass. The supplied image must contain the
project's qualified runtime dependencies; source is mounted read-only.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--label', required=True)
    parser.add_argument('--image', required=True)
    parser.add_argument('--scenario', choices=('mixed','conversation'), default='mixed')
    parser.add_argument('--rate', type=int, default=200)
    parser.add_argument('--seconds', type=int, default=30)
    parser.add_argument('--rounds', type=int, default=5)
    parser.add_argument('--turn-gap', type=float, default=6.25)
    args = parser.parse_args()
    if not re.fullmatch(r'single500-[a-z0-9-]+', args.label):
        parser.error('use a fresh single500- prefixed project label')
    if not (1 <= args.rate <= 400 and 10 <= args.seconds <= 3600
            and 1 <= args.rounds <= 100 and 1 <= args.turn_gap <= 60):
        parser.error('invalid bounded load parameters')
    output = ROOT/'artifacts/single-host-500'
    reports = ROOT/'docs/reviews/evidence/20260913-single-host-500-fixes'
    output.mkdir(parents=True, exist_ok=True); reports.mkdir(parents=True, exist_ok=True)
    report = reports/f'{args.label}-results.json'
    log_path = output/f'{args.label}-compose.log'
    if report.exists() or log_path.exists() or (output/args.label).exists():
        parser.error('preserve existing evidence; choose a fresh label')
    # Never delete another run's containers, network or ledger volume.
    for command in (['docker','ps','-aq'], ['docker','network','ls','-q'], ['docker','volume','ls','-q']):
        existing = subprocess.check_output(command + ['--filter', f'label=com.docker.compose.project={args.label}'], text=True)
        if existing.strip():
            parser.error('project already exists; choose a fresh label')
    env = dict(os.environ, SINGLE500_TEST_IMAGE=args.image, SINGLE500_ISOLATED_MOCK='true',
        SINGLE500_LOAD_LABEL=args.label, SINGLE500_EVENT_LOOP='uvloop',
        SINGLE500_BATCH_CALLBACKS='true', SINGLE500_SCENARIO=args.scenario,
        SINGLE500_TURN_RATE=str(args.rate), SINGLE500_LOAD_SECONDS=str(args.seconds),
        SINGLE500_CONVERSATION_ROUNDS=str(args.rounds), SINGLE500_TURN_GAP_SEC=str(args.turn_gap),
        SINGLE500_REPORT_DIR=str(reports.relative_to(ROOT)))
    compose = ['docker','compose','-p',args.label,'-f','docker-compose.callback-load.yml']
    with log_path.open('x') as log:
        try:
            completed = subprocess.run(compose + ['up','--abort-on-container-exit','--exit-code-from','load'],
                cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                timeout=max(args.seconds, args.rounds*args.turn_gap + 500/args.rate) + 180)
        finally:
            cleanup = subprocess.run(compose + ['down','--volumes','--remove-orphans'],
                cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=120)
    data = json.loads(report.read_text()) if report.exists() else {}
    print(json.dumps(dict(label=args.label, process_exit=completed.returncode,
        cleanup_exit=cleanup.returncode, report=str(report) if report.exists() else None,
        **{key:data.get(key) for key in ('correctness_passed','capacity_slo_passed',
            'conversation_control_slo_passed','synthetic_reply_p99_ms','gateway_delivery_max_ms')})))
    return completed.returncode or cleanup.returncode or (0 if report.exists() else 1)


if __name__ == '__main__':
    raise SystemExit(main())
