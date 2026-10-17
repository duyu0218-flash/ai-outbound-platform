"""Resolve every supported Java Compose profile without starting it or printing secrets."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
profiles = [ ['docker-compose.yml'],
             ['docker-compose.yml', 'docker-compose.java.yml', 'docker-compose.java-services.yml'],
             ['docker-compose.compact.yml'], ['docker-compose.single-host-500.yml'] ]
reports = []
with tempfile.TemporaryDirectory(prefix='java-compose-check-') as folder:
    fixture = Path(folder)/'synthetic.env'
    fixture.write_text('POSTGRES_USER=synthetic\nPOSTGRES_DB=synthetic\nPOSTGRES_PASSWORD=synthetic\nREDISCLI_AUTH=synthetic\n')
    for files in profiles:
        env = dict(os.environ, APP_ENV_FILE='.env.example')
        for name in files + (['docker-compose.compact.yml'] if 'docker-compose.single-host-500.yml' in files else []):
            for key in re.findall(r'\$\{([A-Z0-9_]+)\:?\?', (root/name).read_text()):
                env[key] = str(fixture) if key.endswith('_FILE') else folder if key.endswith('_DIR') else 'example.invalid/fixture@sha256:'+'a'*64 if key.endswith('_IMAGE') else 'synthetic-'+'a'*40
        env.update(NODE_PRIVATE_IP='127.0.0.1', NODE_ID='synthetic-node', LLM_APPROVED_RPM='100', LLM_APPROVED_TPM='10000', LLM_APPROVED_RPS='10')
        command = ['docker', 'compose']
        for name in files: command += ['-f', name]
        config = json.loads(subprocess.run(command+['config','--format','json'],cwd=root,capture_output=True,text=True,check=True,env=env).stdout)
        selected, ports = {}, set()
        for name, service in config['services'].items():
            core = name.startswith(('control-api','task-worker','ai-worker','callback-worker'))
            auxiliary = name.startswith(('ai-agent','voice-gateway','media-','recording-adapter'))
            if not core and not auxiliary: continue
            assert service['command'][:2] == ['java','-jar'], (files,name)
            assert service['healthcheck']['test'][:2] == ['CMD','java'], (files,name)
            settings=service['environment'];port=int(settings['PORT'])
            if service.get('network_mode')=='host':
                assert port not in ports, (files,'colliding host port',port)
                ports.add(port)
                assert settings['JAVA_BIND_ADDRESS'] in {'127.0.0.1','0.0.0.0'}
                if not core: assert settings['JAVA_SERVICE_ROLE'] in {'agent','voice','media','recording'}
            if core:
                assert settings['JAVA_WORKERS_ENABLED'] == ('false' if name.startswith('control-api') and service.get('network_mode')=='host' else 'true')
            if name=='voice-gateway':
                assert any(v['target'] in {'/recordings','/var/lib/freeswitch/recordings'} and not v.get('read_only',False) for v in service['volumes'])
            state_path=(settings.get('VOICE_SECURITY_DB_PATH') if name=='voice-gateway' else settings.get('LLM_QUOTA_DB_PATH') if name.startswith('ai-agent') else settings.get('RECORDING_SPOOL_DIR') if name=='recording-adapter' else None)
            if state_path:
                assert any(not mount.get('read_only',False) and Path(state_path).is_relative_to(mount['target']) for mount in service.get('volumes',[])), (files,name,'persistent state has no writable volume')
            selected[name]={'jar':service['command'][2], 'port':port}
        assert len(selected)>=4
        if files==['docker-compose.yml']: assert 'task-worker' not in config['services']
        if files==['docker-compose.single-host-500.yml']:
            redis=config['services']['redis'];assert '--tls' in redis['healthcheck']['test']
            assert any(v['target']=='/etc/redis/tls' and v.get('read_only') for v in redis['volumes'])
        reports.append(dict(files=files,services=selected))
ns={'m':'http://maven.apache.org/POM/4.0.0'}
for module in ['backend-java','services-java']:
    pom=ET.parse(root/module/'pom.xml').getroot()
    assert pom.findtext('m:parent/m:version',namespaces=ns)=='4.1.1'
    assert pom.findtext('m:properties/m:java.version',namespaces=ns)=='17'
report=dict(passed=True,profiles=reports,java_version=17,spring_boot_version='4.1.1',
            boundary='Compose resolution and command/port/mount checks only. No host deployment, certificate, database cutover or 500-call qualification.')
output=root/'services-java/validation/java-compose.json';output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(report,indent=2)+'\n')
print('Validated',len(reports),'Java Compose selections; no containers started.')
