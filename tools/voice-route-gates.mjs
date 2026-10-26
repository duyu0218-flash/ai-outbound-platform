import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
export function evaluate(input) {
  const rows = input.cases || [];
  const stop = new Set(['stop_contact', 'rejected', 'handoff']);
  const critical = rows.filter((r) => stop.has(r.expected));
  const predicted = rows.filter((r) => stop.has(r.actual));
  const correct = critical.filter((r) => r.actual === r.expected).length;
  const precision = predicted.length ? correct / predicted.length : null;
  const recall = critical.length ? correct / critical.length : null;
  const perIntent = Object.fromEntries(
    [...stop].map((intent) => {
      const expected = rows.filter((r) => r.expected === intent);
      const actual = rows.filter((r) => r.actual === intent);
      const matched = expected.filter((r) => r.actual === intent).length;
      return [
        intent,
        {
          recall: expected.length ? matched / expected.length : null,
          precision: actual.length ? matched / actual.length : null,
        },
      ];
    }),
  );
  const modelReady =
    typeof input.model_version === 'string' &&
    input.model_version.trim() !== '' &&
    typeof input.policy_version === 'string' &&
    input.policy_version.trim() !== '' &&
    (input.route === 'cascade' ||
      (input.route === 'realtime' &&
        ((input.model_tier === 'reasoning' && input.model_reasoning_accuracy >= 0.95) ||
          (input.model_tier === 'fast' && input.low_risk_notification_only === true))));
  const humans = rows.filter((r) => r.expected_answer_class === 'human');
  const humanError = humans.length
    ? humans.filter((r) => r.actual_answer_class === 'machine').length / humans.length
    : null;
  const percentile = (numbers, p) =>
    numbers.length ? numbers.toSorted((a, b) => a - b)[Math.ceil(numbers.length * p) - 1] : null;
  const latency = rows.map((r) => r.effective_reply_ms).filter((n) => Number.isFinite(n) && n >= 0);
  const limits =
    input.route === 'cascade'
      ? [1500, 2200]
      : input.model_tier === 'fast'
        ? [1400, 3000]
        : [2200, 3000];
  const p50 = percentile(latency, 0.5),
    p95 = percentile(latency, 0.95);
  const openings = rows
    .map((r) => r.answer_to_open_audio_ms)
    .filter((n) => Number.isFinite(n) && n >= 0);
  const interrupts = rows.filter((r) => r.interruption_case === true);
  const interruptTimes = interrupts
    .map((r) => r.interrupt_stop_ms)
    .filter((n) => Number.isFinite(n) && n >= 0);
  const openingP95 = percentile(openings, 0.95);
  const interruptP95 = percentile(interruptTimes, 0.95);
  const complete =
    rows.length > 0 &&
    rows.every(
      (r) =>
        r.disclosure_played === true && r.cancel_violations === 0 && r.stop_path_verified === true,
    );
  const gates = [
    { name: 'playback_and_cancellation', passed: complete },
    {
      name: 'critical_intents',
      passed:
        recall !== null &&
        recall >= 0.98 &&
        precision >= 0.95 &&
        humanError !== null &&
        humanError <= 0.005 &&
        modelReady &&
        Object.values(perIntent).every((m) => m.recall >= 0.98 && m.precision >= 0.95),
    },
    {
      name: 'phone_latency',
      passed:
        latency.length === rows.length &&
        rows.length > 0 &&
        p50 <= limits[0] &&
        p95 <= limits[1] &&
        openings.length === rows.length &&
        openingP95 <= 500 &&
        interrupts.length > 0 &&
        interruptTimes.length === interrupts.length &&
        interruptP95 <= 300 &&
        interrupts.every((r) => r.first_interruption_word_preserved === true) &&
        rows.every((r) => ['terminal_acoustic', 'sipp_rtp_capture'].includes(r.evidence_level)),
    },
    { name: 'business_noninferiority', passed: input.business_noninferiority_passed === true },
    { name: 'effective_lead_cost', passed: input.cost_gate_passed === true },
  ];
  let blocked = false;
  for (const gate of gates) {
    gate.reached = !blocked;
    if (!gate.passed) blocked = true;
  }
  return {
    samples: rows.length,
    critical_samples: critical.length,
    per_intent: perIntent,
    model_ready: modelReady,
    precision,
    recall,
    human_misclassification_rate: humanError,
    opening_p95_ms: openingP95,
    interrupt_stop_p95_ms: interruptP95,
    p50_ms: p50,
    p95_ms: p95,
    gates,
    first_failed: gates.find((g) => !g.passed)?.name ?? null,
    trial_eligible: gates.slice(0, 3).every((g) => g.passed) && input.real_vendor_verified === true,
    main_route_eligible:
      gates.every((g) => g.passed) &&
      input.real_vendor_verified === true &&
      input.operator_line_verified === true &&
      (input.business_superiority_passed === true || input.cost_superiority_passed === true),
    thresholds_are_design_targets: true,
  };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [source, output] = process.argv.slice(2);
  if (!source || !output)
    throw new Error('usage: node tools/voice-route-gates.mjs input.json output.json');
  fs.writeFileSync(
    output,
    JSON.stringify(evaluate(JSON.parse(fs.readFileSync(source))), null, 2) + '\n',
  );
}
