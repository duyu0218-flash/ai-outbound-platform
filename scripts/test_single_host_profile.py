import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

import pytest

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('single500_profile',Path(__file__).with_name('check-single-host-500.py'))
profile=importlib.util.module_from_spec(spec);spec.loader.exec_module(profile)


@pytest.fixture
def rendered(tmp_path):
    if not shutil.which('docker'):
        pytest.skip('Docker Compose CLI required for template validation')
    env_file=tmp_path/'synthetic.env'
    env_file.write_text('POSTGRES_USER=synthetic\nPOSTGRES_DB=synthetic\nPOSTGRES_PASSWORD=synthetic\nREDISCLI_AUTH=synthetic\n')
    env=dict(os.environ)
    for file in ['docker-compose.single-host-500.yml','docker-compose.compact.yml']:
        for key in re.findall(r'\$\{([A-Z0-9_]+)',(ROOT/file).read_text()):
            if key.endswith('_FILE'):env[key]=str(env_file)
            elif key.endswith('_DIR'):env[key]=str(tmp_path)
            elif key.endswith('_IMAGE'):env[key]='example.invalid/synthetic@sha256:'+'a'*64
            else:env[key]='synthetic-'+'a'*40
    env.update(NODE_PRIVATE_IP='127.0.0.1',NODE_ID='single-500',LLM_APPROVED_RPM='10000',
               LLM_APPROVED_TPM='13000000',LLM_APPROVED_RPS='500',LLM_MAX_OUTPUT_TOKENS='200')
    result=subprocess.run(['docker','compose','-f','docker-compose.single-host-500.yml','config','--format','json'],
                          cwd=ROOT,env=env,text=True,capture_output=True,check=True)
    return json.loads(result.stdout)


def test_real_compose_merge_budgets_and_single_controller(rendered):
    result=profile.assess(rendered)
    assert result['static_config_passed'],result['blockers']
    assert result['application_db_connections']==64
    assert not result['real_500_call_capacity_verified']


def test_accidental_pool_multiplication_and_media_mismatch_rejected(rendered):
    rendered['services']['ai-worker-1']['environment']['DATABASE_POOL_SIZE']='100'
    rendered['services']['media-12']['environment']['MEDIA_WORKER_CAPACITY']='200'
    result=profile.assess(rendered)
    assert not result['static_config_passed']
    assert any('DB pool' in e for e in result['blockers'])
    assert any('media capacity' in e for e in result['blockers'])


def test_separate_account_quota_volumes_rejected(rendered):
    for mount in rendered['services']['ai-agent-2']['volumes']:
        if mount['target'] == '/var/lib/model-quota':
            mount['source'] = 'other_model_quota'
    result = profile.assess(rendered)
    assert not result['static_config_passed']
    assert any('same writable' in error for error in result['blockers'])
