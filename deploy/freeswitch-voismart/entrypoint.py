"""Render private ESL config without placing a secret in source or process args."""
import os
import ipaddress
from pathlib import Path
import xml.etree.ElementTree as ET

secret = Path('/run/secrets/voismart_esl_password').read_text().strip()
if len(secret) < 24 or not secret.isalnum():
    raise SystemExit('ESL secret must contain at least 24 alphanumeric characters')
for name in ('conf', 'log', 'db', 'run'):
    Path(f'/tmp/fs-{name}').mkdir(exist_ok=True)
root = ET.parse('/opt/media-config/freeswitch.xml')
network = ipaddress.ip_network(os.environ['MEDIA_ESL_ALLOWED_CIDR'], strict=True)
if not network.is_private or network.prefixlen < 16:
    raise SystemExit('Use the specific private Docker project subnet, prefix /16 or narrower')
for node in root.findall('.//node'):
    if node.get('cidr') == '__MEDIA_CIDR__':
        node.set('cidr', str(network))
for param in root.findall('.//param'):
    if param.get('value') == '__ESL_PASSWORD__':
        param.set('value', secret)
root.write('/tmp/fs-conf/freeswitch.xml', encoding='utf-8', xml_declaration=True)
os.execvp('freeswitch', ['freeswitch', '-nf', '-nonat', '-conf', '/tmp/fs-conf',
                       '-log', '/tmp/fs-log', '-db', '/tmp/fs-db', '-run', '/tmp/fs-run'])
