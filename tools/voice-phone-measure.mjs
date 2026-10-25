import fs from 'node:fs';
import { pathToFileURL } from 'node:url';

export function g711(value, type) {
  if (type === 0) {
    const b = ~value & 255;
    const sample = (((b & 15) << 3) + 132) << ((b >> 4) & 7);
    return b & 128 ? 132 - sample : sample - 132;
  }
  const b = value ^ 85;
  const segment = (b >> 4) & 7;
  let sample = (b & 15) << 4;
  sample += segment === 0 ? 8 : 264;
  if (segment > 1) sample <<= segment - 1;
  return b & 128 ? sample : -sample;
}

// Classic PCAP only. Reject unsupported captures instead of manufacturing zero latency.
export function packets(data) {
  if (data.length < 24) throw new Error('truncated PCAP');
  const magic = data.subarray(0, 4).toString('hex');
  const little = ['d4c3b2a1', '4d3cb2a1'].includes(magic);
  if (!little && !['a1b2c3d4', 'a1b23c4d'].includes(magic))
    throw new Error('classic PCAP required');
  const u32 = (n) => (little ? data.readUInt32LE(n) : data.readUInt32BE(n));
  const link = u32(20),
    scale = magic.includes('3c') ? 1e6 : 1000;
  const result = [];
  for (let offset = 24; offset < data.length; ) {
    if (offset + 16 > data.length) throw new Error('truncated packet header');
    const time = u32(offset) * 1000 + u32(offset + 4) / scale;
    const length = u32(offset + 8),
      original = u32(offset + 12);
    offset += 16;
    if (length !== original || offset + length > data.length)
      throw new Error('truncated captured packet');
    const frame = data.subarray(offset, offset + length);
    offset += length;
    let ip = link === 1 ? 14 : link === 113 ? 16 : link === 101 ? 0 : -1;
    if (ip < 0) throw new Error('unsupported link type: ' + link);
    if (frame.length < ip + 20 || frame[ip] >> 4 !== 4) continue;
    const header = (frame[ip] & 15) * 4;
    if (frame[ip + 9] !== 17) continue;
    if (frame.readUInt16BE(ip + 6) & 0x3fff) throw new Error('fragmented UDP capture');
    const udp = ip + header;
    if (header < 20 || frame.length < udp + 8) throw new Error('invalid IP/UDP packet');
    const size = frame.readUInt16BE(udp + 4);
    if (size < 8 || udp + size > frame.length) throw new Error('truncated UDP');
    result.push({
      time,
      source: frame.readUInt16BE(udp),
      destination: frame.readUInt16BE(udp + 2),
      data: frame.subarray(udp + 8, udp + size),
    });
  }
  return result;
}

export function intervals(frames, ssrc, base, threshold = 300) {
  const voices = [],
    seen = new Set();
  let previous = null,
    lost = 0;
  for (const packet of frames) {
    const r = packet.data;
    if (r.length < 12 || r[0] >> 6 !== 2 || r.readUInt32BE(8) !== Number(ssrc)) continue;
    const pt = r[1] & 127;
    if (![0, 8].includes(pt)) throw new Error('SSRC is not G.711 audio');
    const seq = r.readUInt16BE(2);
    const packetKey = seq + ':' + r.readUInt32BE(4);
    if (seen.has(packetKey)) continue;
    seen.add(packetKey);
    if (previous !== null) {
      const gap = (seq - previous + 65536) % 65536;
      if (gap > 1 && gap < 32768) lost += gap - 1;
    }
    previous = seq;
    let header = 12 + (r[0] & 15) * 4;
    if (r[0] & 16) {
      if (r.length < header + 4) throw new Error('truncated RTP extension');
      header += 4 + r.readUInt16BE(header + 2) * 4;
    }
    const end = r.length - (r[0] & 32 ? r.at(-1) : 0);
    if (header > end) throw new Error('invalid RTP header');
    const samples = r.subarray(header, end);
    for (let n = 0; n < samples.length; n += 160) {
      const part = samples.subarray(n, n + 160);
      const rms = Math.sqrt(part.reduce((sum, b) => sum + g711(b, pt) ** 2, 0) / part.length);
      if (rms < threshold) continue;
      const start = packet.time - base + n / 8,
        finish = start + part.length / 8;
      const last = voices.at(-1);
      if (last && start <= last.end + 30) last.end = Math.max(last.end, finish);
      else voices.push({ start, end: finish });
    }
  }
  return { voices: voices.filter((v) => v.end - v.start >= 60), lost, packets: seen.size };
}

export function measure(data, manifest) {
  const frames = packets(data);
  if (
    !Number.isInteger(manifest.customer_ssrc) ||
    !Number.isInteger(manifest.assistant_ssrc) ||
    manifest.customer_ssrc === manifest.assistant_ssrc
  )
    throw new Error('distinct audio SSRCs required');
  const responses = frames.filter((p) => {
    const text = p.data.toString('utf8');
    return (
      /^SIP\/2.0 200 /m.test(text) &&
      /^CSeq:\s*\d+ INVITE\s*$/im.test(text) &&
      (!manifest.call_id || text.includes('Call-ID: ' + manifest.call_id))
    );
  });
  if (!responses.length) throw new Error('INVITE answer event missing');
  const answer = responses[0].time;
  const customer = intervals(frames, manifest.customer_ssrc, answer, manifest.rms_threshold ?? 300);
  const assistant = intervals(
    frames,
    manifest.assistant_ssrc,
    answer,
    manifest.rms_threshold ?? 300,
  );
  if (!customer.packets || !assistant.packets) throw new Error('both RTP directions are required');
  const turns = (manifest.turns || []).map((turn) => {
    if (!(turn.end_ms > turn.start_ms && turn.response_until_ms > turn.end_ms))
      throw new Error('invalid turn window');
    const input = customer.voices.filter((v) => v.start < turn.end_ms && v.end > turn.start_ms);
    const ended = input.length ? Math.max(...input.map((v) => v.end)) : null;
    const proxy =
      ended === null
        ? null
        : assistant.voices.find((v) => v.start >= ended && v.start < turn.response_until_ms);
    const effective =
      ended === null
        ? null
        : assistant.voices.find(
            (v) =>
              v.start >= ended &&
              v.start < turn.response_until_ms &&
              (turn.effective_segments || []).some(
                (label) => v.start >= label.start_ms && v.start < label.end_ms,
              ),
          );
    return {
      id: turn.id,
      user_end_ms: ended,
      audio_onset_proxy_ms: proxy ? proxy.start - ended : null,
      effective_reply_ms: effective ? effective.start - ended : null,
      semantic_label_verified: !!effective,
      // Energy cannot distinguish an old response from a new one. A labelled old-response window is required.
      interrupt_stop_ms:
        turn.interrupt_ms !== undefined && turn.old_response_end_ms !== undefined
          ? turn.old_response_end_ms - turn.interrupt_ms
          : null,
    };
  });
  return {
    profile: manifest.profile,
    model: manifest.model,
    policy_version: manifest.policy_version,
    evidence_level: 'sipp_rtp_capture',
    operator_line_verified: false,
    terminal_acoustic_verified: false,
    answer_to_open_audio_ms: assistant.voices[0]?.start ?? null,
    turns,
    packet_loss: { customer: customer.lost, assistant: assistant.lost },
    note: 'Audio onset is an energy proxy. Effective replies and old-response boundaries require independent labels.',
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [capture, labels, output] = process.argv.slice(2);
  if (!capture || !labels || !output)
    throw new Error(
      'usage: node tools/voice-phone-measure.mjs capture.pcap labels.json output.json',
    );
  fs.writeFileSync(
    output,
    JSON.stringify(
      measure(fs.readFileSync(capture), JSON.parse(fs.readFileSync(labels))),
      null,
      2,
    ) + '\n',
  );
}
