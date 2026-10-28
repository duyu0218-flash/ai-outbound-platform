import assert from 'node:assert/strict';
import { g711, measure } from './voice-phone-measure.mjs';
import { evaluate } from './voice-route-gates.mjs';
assert.equal(g711(255, 0), 0);
assert.equal(g711(213, 8), 8);
function udp(payload, time) {
  const frame = Buffer.alloc(14 + 20 + 8 + payload.length);
  frame[14] = 0x45;
  frame[23] = 17;
  frame.writeUInt16BE(10000, 34);
  frame.writeUInt16BE(10002, 36);
  frame.writeUInt16BE(8 + payload.length, 38);
  payload.copy(frame, 42);
  const packet = Buffer.alloc(16);
  packet.writeUInt32LE(1, 0);
  packet.writeUInt32LE(time * 1000, 4);
  packet.writeUInt32LE(frame.length, 8);
  packet.writeUInt32LE(frame.length, 12);
  return Buffer.concat([packet, frame]);
}
const head = Buffer.alloc(24);
head.writeUInt32LE(0xa1b2c3d4);
head.writeUInt16LE(2, 4);
head.writeUInt16LE(4, 6);
head.writeUInt32LE(65535, 16);
head.writeUInt32LE(1, 20);
const packets = [
  udp(Buffer.from('SIP/2.0 200 OK\r\nCall-ID: test-call\r\nCSeq: 1 INVITE\r\n\r\n'), 0),
];
for (const [ssrc, start, stop] of [
  [1, 100, 300],
  [2, 520, 720],
]) {
  for (let t = start, seq = 0; t < stop; t += 20, seq++) {
    const rtp = Buffer.alloc(172, 128);
    rtp[0] = 128;
    rtp[1] = 0;
    rtp.writeUInt16BE(seq, 2);
    rtp.writeUInt32BE(seq * 160, 4);
    rtp.writeUInt32BE(ssrc, 8);
    packets.push(udp(rtp, t));
  }
}
const capture = Buffer.concat([head, ...packets]);
const manifest = {
  customer_ssrc: 1,
  assistant_ssrc: 2,
  call_id: 'test-call',
  turns: [{ id: 'one', start_ms: 50, end_ms: 400, response_until_ms: 1000 }],
};
const proxy = measure(capture, manifest);
assert.equal(proxy.turns[0].audio_onset_proxy_ms, 220);
assert.equal(proxy.turns[0].effective_reply_ms, null);
manifest.turns[0].effective_segments = [{ start_ms: 500, end_ms: 800 }];
assert.equal(measure(capture, manifest).turns[0].effective_reply_ms, 220);
assert.throws(() => measure(capture.subarray(0, capture.length - 1), manifest), /truncated/);
assert.equal(evaluate({}).trial_eligible, false);
assert.equal(
  evaluate({ cases: [{ expected: 'stop_contact', actual: 'stop_contact', effective_reply_ms: 1 }] })
    .trial_eligible,
  false,
);
console.log(
  'PASS G711, bidirectional PCAP, timing labels, truncation rejection, incomplete evidence gates',
);

const passing = {
  route: 'cascade',
  model_version: 'synthetic-fixed-version',
  policy_version: 'synthetic-fixed-policy',
  real_vendor_verified: true,
  operator_line_verified: true,
  business_noninferiority_passed: true,
  cost_gate_passed: true,
  cost_superiority_passed: true,
  cases: ['stop_contact', 'rejected', 'handoff'].map((intent) => ({
    expected: intent,
    actual: intent,
    expected_answer_class: 'human',
    actual_answer_class: 'human',
    disclosure_played: true,
    cancel_violations: 0,
    stop_path_verified: true,
    effective_reply_ms: 1000,
    answer_to_open_audio_ms: 300,
    interruption_case: true,
    interrupt_stop_ms: 200,
    first_interruption_word_preserved: true,
    evidence_level: 'sipp_rtp_capture',
  })),
};
assert.equal(evaluate(passing).main_route_eligible, true);
passing.cases[0].interrupt_stop_ms = 1000;
assert.equal(evaluate(passing).trial_eligible, false);
passing.cases[0].interrupt_stop_ms = 200;
passing.cases[0].answer_to_open_audio_ms = 1000;
assert.equal(evaluate(passing).trial_eligible, false);
console.log('PASS ordered gates reject slow opening and interruption despite fast replies');

const noVersion = structuredClone(passing);
noVersion.cases.forEach((r) => {
  r.answer_to_open_audio_ms = 300;
});
delete noVersion.model_version;
assert.equal(evaluate(noVersion).trial_eligible, false);
console.log('PASS model and policy identity are required for route eligibility');

// Execute the browser audio-thread contract without a microphone or supplier.
const { readFileSync } = await import('node:fs');
const { runInNewContext } = await import('node:vm');
for (const rate of [16000, 48000]) {
  const messages = [];
  let Processor;
  runInNewContext(readFileSync(new URL('../outbound-mono/frontend/src/audio/pcm-capture.js', import.meta.url), 'utf8'), {
    sampleRate: rate,
    AudioWorkletProcessor: class { port = { postMessage: (message) => messages.push(message) }; },
    registerProcessor: (name, value) => { assert.equal(name, 'trial-pcm-capture'); Processor = value; },
  });
  const capture = new Processor();
  const samples = new Float32Array(rate / 5 + 96).fill(0.5);
  for (let i = 0; i < samples.length; i += 128) capture.process([[samples.subarray(i, i + 128)]]);
  capture.port.onmessage({ data: { type: 'stop' } });
  const expected = Math.floor(samples.length * 16000 / rate);
  assert.equal(messages.at(-1).type, 'drained');
  assert.equal(messages.at(-1).samples, expected);
  assert.equal(messages.slice(0, -1).reduce((n, m) => n + m.bytes.byteLength / 2, 0), expected);
  assert.equal(messages[0].bytes.byteLength, 3200);
  assert.equal(new DataView(messages[0].bytes).getInt16(0, true), 16384);
  const count = messages.length;
  assert.equal(capture.process([[samples]]), false);
  capture.port.onmessage({ data: { type: 'stop' } });
  assert.equal(messages.length, count);
}
console.log('PASS AudioWorklet 16/48 kHz packet sizes, final partial drain, PCM endian, idempotent stop');
