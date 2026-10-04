"""Pure, inspectable gates for a scheduled synthetic load (not media capacity)."""
import math


def assess_load(*, planned_count, planned_duration_sec, generator_lags_ms, acknowledged):
    """Acknowledged contains (scheduled_seconds, first_HTTP_200_seconds).

    Both timestamps are relative to the load start. Only first durable ACKs
    count, not retries or eventual database completion after the load stops.
    """
    counts = dict(planned_count=planned_count, generator_sample_count=len(generator_lags_ms),
                  acknowledged_count=len(acknowledged))
    finite_lags = [x for x in generator_lags_ms if math.isfinite(x)]
    diagnostics = dict(
        minimum_generator_lag_ms=min(finite_lags) if finite_lags else None,
        negative_generator_lag_count=sum(x < 0 for x in finite_lags),
        nonfinite_generator_lag_count=len(generator_lags_ms)-len(finite_lags),
        nonfinite_ack_sample_count=sum(not (math.isfinite(s) and math.isfinite(a)) for s,a in acknowledged),
        ack_before_schedule_count=sum(math.isfinite(s) and math.isfinite(a) and a < s for s,a in acknowledged),
        negative_schedule_count=sum(math.isfinite(s) and s < 0 for s,_ in acknowledged))
    reasons = []
    if type(planned_count) is not int or planned_count <= 0:reasons.append('invalid_planned_count')
    if not math.isfinite(planned_duration_sec) or planned_duration_sec <= 0:reasons.append('invalid_planned_duration')
    if len(generator_lags_ms) != planned_count:reasons.append('generator_sample_count_mismatch')
    if len(acknowledged) != planned_count:reasons.append('acknowledged_count_mismatch')
    for key in ('negative_generator_lag_count','nonfinite_generator_lag_count',
                'nonfinite_ack_sample_count','ack_before_schedule_count','negative_schedule_count'):
        if diagnostics[key]:reasons.append(key)
    if reasons:
        return dict(load_validity_passed=False, reason='missing or invalid load samples',
            failure_reasons=reasons, **counts, **diagnostics)
    def p99(values):
        ordered = sorted(values)
        return ordered[min(len(ordered)-1, int(len(ordered)*.99))]
    ack_lags = [(a-s)*1000 for s,a in acknowledged]
    duration = max(planned_duration_sec, max(a for _,a in acknowledged))
    offered_ratio = planned_duration_sec / duration
    windows = {}
    for scheduled, received in acknowledged:
        bucket = int(scheduled // 10) * 10
        window = windows.setdefault(bucket, dict(planned=0, acknowledged_by_deadline=0))
        window['planned'] += 1
        if received <= min(bucket + 10, planned_duration_sec) + 1:
            window['acknowledged_by_deadline'] += 1
    generator_p99 = p99(generator_lags_ms)
    ack_p99 = p99(ack_lags)
    if offered_ratio < .95:reasons.append('offered_rate_below_minimum')
    if generator_p99 > 100:reasons.append('generator_lag_p99_above_limit')
    if ack_p99 > 1000:reasons.append('scheduled_to_ack_p99_above_limit')
    if any(w['acknowledged_by_deadline'] < .95*w['planned'] for w in windows.values()):
        reasons.append('ten_second_window_below_minimum')
    return dict(load_validity_passed=not reasons, failure_reasons=reasons, **counts, **diagnostics,
        planned_duration_seconds=planned_duration_sec,
        actual_ack_duration_seconds=duration, offered_rate_ratio=offered_ratio,
        actual_acknowledged_per_second=planned_count/duration,
        generator_lag_p99_ms=generator_p99, scheduled_to_ack_p99_ms=ack_p99,
        minimum_offered_rate_ratio=.95, generator_lag_limit_ms=100,
        scheduled_to_ack_limit_ms=1000, ten_second_windows=windows)
