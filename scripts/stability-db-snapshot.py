#!/usr/bin/env python3
"""Read-only PostgreSQL 16/host evidence. DSN comes from STABILITY_PG_DSN, never output."""
import argparse
import json
import os
from pathlib import Path
import time


QUERIES = {
    'waits': "SELECT state, wait_event_type, wait_event, count(*) AS sessions FROM pg_stat_activity WHERE pid <> pg_backend_pid() GROUP BY 1,2,3",
    'wal': 'SELECT * FROM pg_stat_wal',
    'checkpoints': 'SELECT * FROM pg_stat_bgwriter',
    'io': 'SELECT * FROM pg_stat_io',
    'database': 'SELECT numbackends, xact_commit, xact_rollback, deadlocks, temp_bytes, blk_read_time, blk_write_time FROM pg_stat_database WHERE datname=current_database()',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--seconds', type=int, default=60)
    parser.add_argument('--interval', type=float, default=5)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 86400 or not 1 <= args.interval <= 60:
        parser.error('bounded duration and interval required')
    import psycopg
    from psycopg.rows import dict_row
    started = time.monotonic()
    with args.output.open('x') as output:
        while True:
            sample = dict(at=time.time())
            try:
                with psycopg.connect(os.environ['STABILITY_PG_DSN'], connect_timeout=3,
                        options='-c default_transaction_read_only=on -c statement_timeout=2000', row_factory=dict_row) as db:
                    for name, sql in QUERIES.items():
                        sample[name] = db.execute(sql).fetchall()
            except Exception as exc:
                sample['error_type'] = type(exc).__name__  # Do not print credentials/SQL error details.
            for name in ('cpu.stat', 'memory.current', 'memory.events', 'io.stat'):
                path = Path('/sys/fs/cgroup')/name
                if path.is_file():sample[name] = path.read_text()[:16384]
            output.write(json.dumps(sample, default=str)+'\n');output.flush()
            remaining = args.seconds-(time.monotonic()-started)
            if remaining <= 0:break
            time.sleep(min(args.interval,remaining))


if __name__ == '__main__':
    main()
