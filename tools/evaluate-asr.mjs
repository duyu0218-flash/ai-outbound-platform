import { fs, path, args, readJSON, sha, assert } from "./lib/common.mjs";
// Generated offline from the Unicode CaseFolding mapping; no model/audio requests.
import casefold from "./unicode-casefold.json" with { type: "json" };
export const NORMALIZATION = "nfkc-casefold-remove-whitespace-punctuation-v1";
export function normalize(text) {
  return [...text.normalize("NFKC")]
    .map((c) => casefold[c] ?? c)
    .join("")
    .replace(/[\p{White_Space}\p{P}]/gu, "");
}
export function distance(ref, hyp) {
  let a = [...ref],
    b = [...hyp];
  if (a.length < b.length) [a, b] = [b, a];
  let previous = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const current = [i];
    for (let j = 1; j <= b.length; j++)
      current[j] = Math.min(
        current[j - 1] + 1,
        previous[j] + 1,
        previous[j - 1] + (a[i - 1] !== b[j - 1]),
      );
    previous = current;
  }
  return previous[b.length];
}
const numeric = (v, name, positive = false) => {
  assert(
    typeof v === "number" &&
      Number.isFinite(v) &&
      v >= 0 &&
      (!positive || v > 0),
    name + " must be finite numeric data",
  );
  return v;
};
const text = (v, name) => {
  assert(typeof v === "string" && v.trim(), name + " must be nonempty text");
  return v;
};
const ratio = (n, d) => (d ? n / d : null),
  percentile = (v, q) =>
    v.length ? [...v].sort((a, b) => a - b)[Math.ceil(v.length * q) - 1] : null;
function unique(items, label) {
  assert(
    Array.isArray(items) && items.length,
    label + " must be nonempty list",
  );
  const map = new Map();
  for (const item of items) {
    assert(item && typeof item === "object" && !Array.isArray(item));
    const id = text(item.id, label + ".id");
    assert(!map.has(id), "Duplicate " + label + " id");
    map.set(id, item);
  }
  return map;
}
function segments(item, duration) {
  assert(Array.isArray(item.segments), "Segments required");
  for (const r of item.segments) {
    assert(r && typeof r.text === "string");
    const start = numeric(r.start_ms, "start_ms"),
      end = numeric(r.end_ms, "end_ms");
    assert(start <= end && end <= duration, "Invalid segment interval");
    if (r.speaker_role !== undefined && r.speaker_role !== null)
      text(r.speaker_role, "speaker_role");
  }
  return [...item.segments].sort(
    (a, b) => a.start_ms - b.start_ms || a.end_ms - b.end_ms,
  );
}
const combined = (rows) => normalize(rows.map((r) => r.text).join(""));
export function evaluate(
  manifestPath,
  resultPaths,
  { verifyAudio = false } = {},
) {
  const m = readJSON(manifestPath);
  assert(
    m.schema_version === 1 &&
      ["synthetic", "recorded"].includes(m.dataset_kind),
    "Invalid manifest",
  );
  const samples = unique(m.samples, "samples"),
    prepared = new Map();
  for (const [id, s] of samples) {
    assert(
      s.authorized === true && s.human_verified === true,
      "Authorization and human verification required",
    );
    assert(/^[0-9a-f]{64}$/.test(text(s.audio_sha256, "audio_sha256")));
    const duration = numeric(s.duration_ms, "duration_ms", true),
      reference = segments(s, duration),
      ref = combined(reference);
    assert(Array.isArray(s.keywords ?? []));
    const keywords = new Set(
      (s.keywords ?? []).map((k) => normalize(text(k, "keyword"))),
    );
    assert(
      [...keywords].every((k) => k && ref.includes(k)),
      "Keyword must occur in reference",
    );
    if (verifyAudio)
      assert.equal(
        sha(
          path.resolve(
            path.dirname(manifestPath),
            text(s.audio_path, "audio_path"),
          ),
        ),
        s.audio_sha256,
        "Audio hash mismatch",
      );
    prepared.set(id, { s, duration, reference, ref, keywords });
  }
  assert(resultPaths.length, "At least one provider result required");
  const dataset = sha(manifestPath),
    identities = new Set(),
    providers = [];
  for (const resultPath of resultPaths) {
    const d = readJSON(resultPath);
    assert(
      d.schema_version === 1 && d.dataset_sha256 === dataset,
      "Result dataset mismatch",
    );
    const identity = ["provider", "model", "revision"].map((k) =>
        text(d[k], k),
      ),
      ik = JSON.stringify(identity);
    assert(!identities.has(ik), "Duplicate provider/model/revision");
    identities.add(ik);
    const items = unique(d.items, "results");
    assert(
      [...items.keys()].every((k) => samples.has(k)),
      "Unknown sample id",
    );
    const totals = {
        edits: 0,
        reference_chars: 0,
        keyword_hits: 0,
        keyword_total: 0,
      },
      durations = [],
      costs = [],
      rtfs = [],
      details = [];
    for (const [id, { s, duration, reference, ref, keywords }] of prepared) {
      const item = items.get(id),
        status = item ? item.status : "missing";
      assert(
        ["missing", "ok", "error"].includes(status) &&
          (!item || status !== "missing"),
      );
      if (item)
        assert.equal(
          item.audio_sha256,
          s.audio_sha256,
          "Result audio mismatch",
        );
      const hypothesis = status === "ok" ? segments(item, duration) : [],
        hyp = combined(hypothesis),
        edits = distance(ref, hyp),
        hits = [...keywords].filter((k) => hyp.includes(k)).length,
        n = [...ref].length;
      totals.edits += edits;
      totals.reference_chars += n;
      totals.keyword_hits += hits;
      totals.keyword_total += keywords.size;
      let role = null;
      if (
        reference.length &&
        [...reference, ...hypothesis].every((r) => r.speaker_role)
      ) {
        const roles = new Set(
          [...reference, ...hypothesis].map((r) => r.speaker_role),
        );
        role = ratio(
          [...roles].reduce(
            (a, r) =>
              a +
              distance(
                combined(reference.filter((v) => v.speaker_role === r)),
                combined(hypothesis.filter((v) => v.speaker_role === r)),
              ),
            0,
          ),
          n,
        );
      }
      let elapsed = null;
      if (
        status === "ok" &&
        item.processing_ms !== undefined &&
        item.processing_ms !== null
      ) {
        elapsed = numeric(item.processing_ms, "processing_ms");
        durations.push(elapsed);
        rtfs.push(elapsed / duration);
      }
      if (item?.cost_cny !== undefined && item.cost_cny !== null)
        costs.push(numeric(item.cost_cny, "cost_cny"));
      details.push({
        id,
        status,
        edits,
        reference_chars: n,
        cer: ratio(edits, n),
        role_text_cer: role,
        keyword_hits: hits,
        keyword_total: keywords.size,
        processing_ms: elapsed,
      });
    }
    const failed = details.filter((r) => r.status !== "ok").length,
      sum = costs.reduce((a, b) => a + b, 0);
    providers.push({
      provider: identity[0],
      model: identity[1],
      revision: identity[2],
      result_sha256: sha(resultPath),
      sample_count: samples.size,
      failed_or_missing: failed,
      complete: failed === 0,
      ...totals,
      cer: ratio(totals.edits, totals.reference_chars),
      keyword_recall: ratio(totals.keyword_hits, totals.keyword_total),
      processing_measured_count: durations.length,
      offline_processing_p50_ms: percentile(durations, 0.5),
      offline_processing_p95_ms: percentile(durations, 0.95),
      offline_rtf_p95: percentile(rtfs, 0.95),
      cost_measured_count: costs.length,
      total_cost_cny: costs.length === samples.size ? sum : null,
      cost_per_audio_minute_cny:
        costs.length === samples.size
          ? ratio(
              sum,
              [...prepared.values()].reduce((a, v) => a + v.duration, 0) /
                60000,
            )
          : null,
      samples: details,
    });
  }
  return {
    schema_version: 1,
    dataset_sha256: dataset,
    dataset_kind: m.dataset_kind,
    normalization: NORMALIZATION,
    unicode_casefold_version: "14.0.0",
    unicode_normalization_runtime: process.versions.unicode,
    audio_files_verified: verifyAudio,
    real_line_verified: false,
    production_approved: false,
    providers,
  };
}
if (process.argv[1]?.endsWith("evaluate-asr.mjs")) {
  const a = args();
  assert(typeof a.results === "string", "Use comma-separated result paths");
  const report = evaluate(a.manifest, a.results.split(","), {
    verifyAudio: a["verify-audio"] === true,
  });
  fs.mkdirSync(path.dirname(a.report), { recursive: true });
  fs.writeFileSync(a.report, JSON.stringify(report, null, 2) + "\n", {
    flag: "wx",
    mode: 0o600,
  });
  console.log("ASR report written; real line and production remain unverified");
  if (report.providers.some((p) => !p.complete)) process.exitCode = 1;
}
