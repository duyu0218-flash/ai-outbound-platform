"""Read-only Compose resolution; never starts containers or prints credentials."""
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
command = ['docker', 'compose', '-f', 'docker-compose.yml', '-f', 'docker-compose.java.yml', '-f', 'docker-compose.java-services.yml', 'config', '--format', 'json']
result = subprocess.run(command, cwd=root, capture_output=True, text=True, check=True,
                        env=dict(os.environ, APP_ENV_FILE='.env.example'))
config = json.loads(result.stdout)
services = {}
for name in ['control-api', 'ai-agent', 'recording-adapter', 'voice-gateway']:
    service = config['services'][name]
    assert service['command'][0] == 'java', name
    assert service['healthcheck']['test'][1] == 'java', name
    assert Path(service['build']['context']).resolve() == root, name
    services[name] = {key: service[key] for key in ['command', 'healthcheck']}
assert 'task-worker' not in config['services'], 'legacy Python queue owner must not be active'
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
for module in ['backend-java', 'services-java']:
    pom = ET.parse(root/module/'pom.xml').getroot()
    assert pom.findtext('m:parent/m:version', namespaces=ns) == '4.1.1'
    assert pom.findtext('m:properties/m:java.version', namespaces=ns) == '17'
report = dict(passed=True, services=services, java_version=17, spring_boot_version='4.1.1', legacy_worker_active=False,
              boundary='Configuration resolution only; no image startup, host mounts, database cutover or deployment verified.')
output = root/'services-java/validation/java-compose.json'
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps(report))
