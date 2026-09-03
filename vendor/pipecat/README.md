# Controlled Pipecat patch

See [build, audit and deployment instructions](../../docs/pipecat-controlled-patch.md).
`manifest.json` pins the official PyPI wheel and the deterministic patched wheel.
The upstream BSD-2-Clause license is retained in this directory and in the wheel.

The following upstream tests are copied verbatim from `pipecat-ai/pipecat` tag
`v1.8.1`, under the same BSD-2-Clause license, into `voice_gateway/tests/`:

- `tests/test_utils_string.py` → `test_upstream_pipecat_string.py`, SHA-256
  `52547c0df169d9dff0b602ca6b7ff08bc7b5d279e582adf71570fc80fc1db47a`.
- `tests/test_simple_text_aggregator.py` → `test_upstream_pipecat_aggregator.py`, SHA-256
  `1a42d6547de2223c511b47ee4481d4d88167fa9a71837d92a9d9a9618794ac7a`.

Our additional contract tests are in `test_sentence_boundaries.py` and
`test_pipecat_wheel.py`. No upstream implementation fix is attributed to us.
