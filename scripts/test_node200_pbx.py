import importlib.util
from pathlib import Path
spec=importlib.util.spec_from_file_location('node200_pbx',Path(__file__).with_name('check-node200-pbx.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


def test_local_fixture_cannot_qualify_for_200():
    result=module.assess(Path(__file__).resolve().parents[1]/'deploy/freeswitch-voismart/freeswitch.xml')
    assert not result['static_config_passed'] and len(result['blockers'])==3


def test_expanded_core_budget_and_unresolved_variables(tmp_path):
    import pytest
    path=tmp_path/'core.xml'
    path.write_text('<configuration name="switch.conf"><settings><param name="max-sessions" value="500"/><param name="sessions-per-second" value="20"/><param name="rtp-start-port" value="20000"/><param name="rtp-end-port" value="22000"/></settings></configuration>')
    # Root may itself be the switch.conf configuration, as in an exported core.
    assert module.assess(path)['static_config_passed']
    path.write_text(path.read_text().replace('value="500"','value="$${sessions}"'))
    with pytest.raises(ValueError):module.assess(path)
