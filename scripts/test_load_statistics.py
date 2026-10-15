from load_statistics import Samples, ProcessLog
import subprocess
import sys


def test_bounded_samples_preserve_lifetime_count_max_and_quantile():
    samples = Samples(limit=10)
    values = [100000]+list(range(1,10001))
    for value in values:
        samples.append(value)
    assert len(samples.values) == 10
    assert len(samples) == len(values)
    assert samples.total == sum(values)
    assert samples.maximum == 100000
    exact = sorted(values)[int(len(values)*.99)]
    assert exact <= samples.quantile(.99) <= (exact+1)*1.01


def test_short_sample_quantiles_remain_exact():
    samples = Samples()
    for value in (4,1,3,2):samples.append(value)
    assert samples.quantile(.5) == 3
    assert samples.quantile(.99) == 4


def test_capture_counts_retry_before_rotation(tmp_path):
    capture = ProcessLog(tmp_path/'child.log')
    capture.handler.maxBytes = 100
    process = subprocess.Popen([sys.executable,'-c',
        "print(('AI transport retry error_type=ConnectError\\n')*100)"], stdout=subprocess.PIPE)
    capture.attach(process.stdout)
    process.wait(timeout=10)
    capture.close()
    assert capture.retries == 100 and capture.error is None
    assert len(list(tmp_path.glob('child.log*'))) <= 4
