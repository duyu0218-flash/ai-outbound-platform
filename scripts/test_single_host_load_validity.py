import pytest
from scripts.single_host_load_validity import assess_load


def evaluate(*, lag=0, speed=1, missing=0):
    count = 6000
    samples = [(i/200, i/200/speed+lag/1000+.02) for i in range(count-missing)]
    return assess_load(planned_count=count, planned_duration_sec=30,
        generator_lags_ms=[lag]*(count-missing), acknowledged=samples)


def test_real_offered_load_and_ack_samples_are_required():
    result = evaluate()
    assert result['load_validity_passed']
    assert result['actual_acknowledged_per_second'] == pytest.approx(6000/30.015)
    assert result['acknowledged_count'] == 6000


@pytest.mark.parametrize('kwargs', [dict(lag=150),dict(speed=.5),dict(missing=1)])
def test_eventual_completion_cannot_hide_a_slow_or_incomplete_generator(kwargs):
    assert not evaluate(**kwargs)['load_validity_passed']


def test_one_early_window_cannot_be_repaired_by_catching_up_later():
    samples = [(i/200, i/200+(4 if i<2000 else .01)) for i in range(6000)]
    result=assess_load(planned_count=6000,planned_duration_sec=30,
        generator_lags_ms=[0]*6000,acknowledged=samples)
    assert not result['load_validity_passed']
    assert result['ten_second_windows'][0]['acknowledged_by_deadline'] < 2000*.95


def test_nonfinite_or_empty_samples_cannot_pass():
    for samples in ([],[(0,float('nan'))],[(0,float('inf'))],[(1,0)]):
        assert not assess_load(planned_count=1,planned_duration_sec=1,
            generator_lags_ms=[0],acknowledged=samples)['load_validity_passed']


def test_early_sending_is_preserved_as_failure_evidence():
    result=assess_load(planned_count=1,planned_duration_sec=1,
        generator_lags_ms=[-.01],acknowledged=[(0,.02)])
    assert not result['load_validity_passed']
    assert result['minimum_generator_lag_ms'] == -.01
    assert result['negative_generator_lag_count'] == 1
    assert 'negative_generator_lag_count' in result['failure_reasons']
