#!/usr/bin/env python3
"""Inspect the fleet or drain ONE node before a rolling update. No SSH or secrets in argv."""
import argparse
import json
from pathlib import Path
import time
import urllib.error
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def request(endpoint, path, token='', method='GET'):
    headers = {'Authorization': f'Bearer {token}'} if token else {}
    req = urllib.request.Request(endpoint.rstrip('/') + path, headers=headers, method=method,
                                 data=b'' if method == 'POST' else None)
    with urllib.request.build_opener(NoRedirect, urllib.request.ProxyHandler({})).open(req, timeout=5) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['status', 'drain', 'resume'])
    parser.add_argument('--roster', required=True)
    parser.add_argument('--node')
    parser.add_argument('--admin-token-file')
    parser.add_argument('--wait-seconds', type=int, default=0)
    args = parser.parse_args()
    nodes = json.loads(Path(args.roster).read_text())
    if args.node:
        nodes = [n for n in nodes if n['id'] == args.node]
    if not nodes:
        parser.error('unknown node')
    if args.action != 'status' and (len(nodes) != 1 or not args.node or not args.admin_token_file):
        parser.error('drain/resume requires exactly one --node and an --admin-token-file')
    token = Path(args.admin_token_file).read_text().strip() if args.admin_token_file else ''
    failed = False
    for node in nodes:
        try:
            if args.action == 'status':
                data = request(node['endpoint'], '/readyz')
                print(json.dumps({'node': node['id'], 'ready': data.get('status'),
                                  'identity_matches': data.get('node_id') == node['id'],
                                  'capacity': data.get('call_capacity')}))
            else:
                enabled = 'true' if args.action == 'drain' else 'false'
                data = request(node['endpoint'], '/v1/admin/drain?enabled=' + enabled, token, 'POST')
                print(json.dumps({'node': node['id'], **data}))
                if args.action == 'drain' and args.wait_seconds:
                    deadline = time.monotonic() + args.wait_seconds
                    while True:
                        state = request(node['endpoint'], '/v1/admin/security', token)
                        # An empty in-memory map is NOT proof after a restart.
                        if state['active_attempts'] == 0 and state['pending_callbacks'] == 0:
                            print(json.dumps({'node': node['id'], 'safe_to_stop': True})); break
                        if time.monotonic() >= deadline:
                            raise TimeoutError('drain still has durable calls/callbacks')
                        time.sleep(1)
        except Exception as exc:
            print(json.dumps({'node': node['id'], 'error_type': type(exc).__name__}))
            failed = True
    return int(failed)


if __name__ == '__main__':
    raise SystemExit(main())
