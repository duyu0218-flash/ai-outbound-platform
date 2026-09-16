#!/usr/bin/env python3
"""Supervise an explicitly configured 8/24h workload on an independent load host.

The manifest supplies argv (never a shell string), immutable image digests and
exact target. This runner owns only its subprocess group and new output folder.
Its completion is NOT a media/capacity pass; exported SIP/RTP/trace evidence must
be reviewed against the acceptance matrix alongside this durable journal.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import threading
import time


class RotatingLog:
    def __init__(self, folder, max_bytes=64*1024*1024):
        self.folder, self.max_bytes, self.part, self.size = folder, max_bytes, 0, 0
        self.file = None

    def write(self, data):
        while data:
            if self.file is None or self.size == self.max_bytes:
                self.close()
                self.part += 1
                self.file = (self.folder/f'workload-{self.part:05d}.log').open('xb')
                self.size = 0
            piece, data = data[:self.max_bytes-self.size], data[self.max_bytes-self.size:]
            self.file.write(piece)
            self.size += len(piece)
            self.file.flush()

    def close(self):
        if self.file:
            self.file.flush()
            os.fsync(self.file.fileno())
            self.file.close()
            self.file = None


def validate(manifest, smoke=False):
    argv = manifest.get('argv')
    if not isinstance(argv, list) or not argv or not all(isinstance(s,str) and s for s in argv):
        raise ValueError('argv must be a nonempty array; shell strings are not accepted')
    duration = manifest.get('duration_seconds')
    if type(duration) is not int or (not smoke and duration not in (28800,86400)) or (smoke and not 1 <= duration <= 60):
        raise ValueError('use 28800/86400 seconds, or --smoke with 1..60 seconds')
    if not manifest.get('target_host'):
        raise ValueError('an explicit target_host is required')
    if not smoke and manifest['target_host'] in {socket.gethostname(), 'localhost','127.0.0.1','::1'}:
        raise ValueError('qualification requires an independent load host')
    images = manifest.get('images', {})
    required = {'backend','agent','voice_gateway','recording_adapter','freeswitch','postgres','redis'}
    if not required <= images.keys() or not all(re.fullmatch(r'.+@sha256:[a-f0-9]{64}', value) for value in images.values()):
        raise ValueError('all seven service images must have immutable SHA-256 digests')
    return duration


def supervise(manifest, output, *, smoke=False):
    duration = validate(manifest, smoke)
    output.mkdir(parents=True, exist_ok=False)
    manifest_bytes = json.dumps(manifest, sort_keys=True, ensure_ascii=False).encode()
    (output/'manifest.json').write_bytes(manifest_bytes)
    journal = (output/'journal.jsonl').open('x')
    def record(**values):
        journal.write(json.dumps({'at':time.time(), **values}, ensure_ascii=False)+'\n')
        journal.flush()
        os.fsync(journal.fileno())
    child = None
    reader = None
    interrupted = False
    reader_error = []
    def stop(_signum, _frame):
        nonlocal interrupted
        interrupted = True
    handlers = {sig:signal.signal(sig,stop) for sig in (signal.SIGINT,signal.SIGTERM)}
    started = time.monotonic()
    log = RotatingLog(output)
    reached_duration = False
    process_exit = None
    try:
        record(event='started', load_host=socket.gethostname(), target_host=manifest['target_host'],
            manifest_sha256=hashlib.sha256(manifest_bytes).hexdigest(), duration_seconds=duration, smoke=smoke)
        child = subprocess.Popen(manifest['argv'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            start_new_session=True, env={**os.environ, 'ACCEPTANCE_OUTPUT_DIR':str(output.resolve())})
        def capture():
            try:
                while chunk := child.stdout.read1(65536):
                    log.write(chunk)
            except BaseException as exc:
                reader_error.append(type(exc).__name__)
            finally:
                log.close()
        reader = threading.Thread(target=capture, daemon=True)
        reader.start()
        while not interrupted:
            elapsed = time.monotonic()-started
            process_exit = child.poll()
            record(event='checkpoint', elapsed_seconds=elapsed, process_exit=process_exit,
                   log_parts=log.part, capture_errors=list(reader_error))
            if process_exit is not None or reader_error:
                break
            if elapsed >= duration:
                reached_duration = True
                break
            time.sleep(min(1, duration-elapsed))
    finally:
        if child is not None:
            # Only the process group created by this run is signalled.
            try:
                os.killpg(child.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                child.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait(timeout=5)
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            if reader:
                reader.join(timeout=5)
                if reader.is_alive():
                    reader_error.append('log capture did not terminate')
            child.stdout.close()
        result = dict(supervision_completed=reached_duration and not interrupted and not reader_error,
            interrupted=interrupted, elapsed_seconds=time.monotonic()-started,
            process_exit_before_cleanup=process_exit, capture_errors=reader_error,
            real_500_capacity_verified=False, smoke=smoke,
            qualification_status='unverified: review exported SIP/RTP/cloud/recording traces',
            cleanup_scope='only this run process group; no database or shared resource deletion')
        record(event='finished', **result)
        journal.close()
        (output/'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        for sig,handler in handlers.items():
            signal.signal(sig,handler)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--smoke', action='store_true')
    args = parser.parse_args()
    result = supervise(json.loads(args.manifest.read_text()),args.output,smoke=args.smoke)
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result['supervision_completed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
