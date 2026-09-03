#!/usr/bin/env python3
"""Build a reproducible, hash-pinned Pipecat wheel without changing site-packages.

Only stdlib is needed. The upstream archive is never executed or extracted.
Use --upstream-wheel for an offline build; --allow-unpinned-output is only for
maintainers generating a new reviewed manifest digest, never an install/CI path.
"""

from __future__ import annotations

import argparse
import ast
import base64
import csv
import hashlib
import io
import json
from pathlib import Path
import textwrap
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RECIPE = ROOT / "vendor/pipecat"


def replace_node(source: str, name: str, replacement: str = "") -> str:
    nodes = [n for n in ast.walk(ast.parse(source))
             if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name == name]
    if len(nodes) != 1:
        raise ValueError(f"expected exactly one upstream definition: {name}")
    node = nodes[0]
    start = min([node.lineno, *[d.lineno for d in node.decorator_list]]) - 1
    lines = source.splitlines(keepends=True)
    lines[start:node.end_lineno] = [textwrap.indent(replacement, " " * node.col_offset)]
    return "".join(lines)


def build(upstream: bytes, manifest: dict) -> bytes:
    if hashlib.sha256(upstream).hexdigest() != manifest["upstream_sha256"]:
        raise ValueError("upstream wheel SHA-256 mismatch")
    with zipfile.ZipFile(io.BytesIO(upstream)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("duplicate upstream ZIP members")
        files = {name: archive.read(name) for name in names}
    old_info = f"pipecat_ai-{manifest['upstream_version']}.dist-info"
    new_info = f"pipecat_ai-{manifest['version']}.dist-info"
    metadata = files[f"{old_info}/METADATA"].decode()
    needle = "Requires-Dist: nltk<4,>=3.10.0\n"
    if metadata.count(needle) != 1:
        raise ValueError("upstream NLTK requirement changed")
    metadata = metadata.replace(needle, "").replace(
        f"Version: {manifest['upstream_version']}\n", f"Version: {manifest['version']}\n", 1
    )
    files[f"{old_info}/METADATA"] = metadata.encode()

    path = "pipecat/utils/string.py"
    source = files[path].decode()
    source = replace_node(source, "_sent_tokenizer")
    source = replace_node(source, "match_endofsentence")
    source = source.replace("import threading\n", "")
    source = source.replace("from collections.abc import Callable, Sequence", "from collections.abc import Sequence")
    source = source.replace("from functools import cache\n", "")
    source = source.replace("from loguru import logger", "from pipecat.utils._sentence import match_endofsentence")
    source = source.replace("_load_lock = threading.Lock()", "")
    doc = ast.parse(source).body[0]
    lines = source.splitlines(keepends=True)
    lines[doc.lineno - 1:doc.end_lineno] = ['"""Text utilities; sentence boundaries use the outbound no-I/O scanner."""\n']
    files[path] = "".join(lines).encode()
    files["pipecat/utils/_sentence.py"] = (RECIPE / "sentence.py").read_bytes()
    files["pipecat/utils/prewarm.py"] = (RECIPE / "prewarm.py").read_bytes()

    path = "pipecat/utils/text/simple_text_aggregator.py"
    source = files[path].decode().replace("from pipecat.utils.string import", "from pipecat.utils._sentence import CLOSERS\nfrom pipecat.utils.string import", 1)
    source = replace_node(source, "_check_sentence_with_lookahead", (RECIPE / "lookahead.txt").read_text())
    source = source.replace("before calling NLTK", "before checking the sentence boundary")
    files[path] = source.encode()

    files = {name.replace(old_info + "/", new_info + "/", 1): data for name, data in files.items()
             if name != f"{old_info}/RECORD"}
    files[f"{new_info}/outbound-patch.json"] = json.dumps({
        "upstream_version": manifest["upstream_version"],
        "upstream_sha256": manifest["upstream_sha256"],
        "version": manifest["version"],
        "reason": "Remove NLTK; deterministic in-memory sentence boundaries",
    }, sort_keys=True, indent=2).encode() + b"\n"
    # Preserve the upstream BSD license and all non-patched package contents.
    record = io.StringIO(newline="")
    writer = csv.writer(record, lineterminator="\n")
    for name, data in sorted(files.items()):
        digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode()
        writer.writerow([name, "sha256=" + digest, len(data)])
        if name.endswith(".py"):
            ast.parse(data, filename=name)
    writer.writerow([f"{new_info}/RECORD", "", ""])
    files[f"{new_info}/RECORD"] = record.getvalue().encode()
    output = io.BytesIO()
    # Stored entries avoid compression-library differences across Python/OS.
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, data)
    return output.getvalue()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream-wheel", type=Path)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "artifacts/wheels")
    parser.add_argument("--allow-unpinned-output", action="store_true")
    args = parser.parse_args()
    manifest = json.loads((RECIPE / "manifest.json").read_text())
    cache = ROOT / "artifacts/upstream/pipecat_ai-1.8.1-py3-none-any.whl"
    if args.upstream_wheel:
        upstream = args.upstream_wheel.read_bytes()
    elif cache.exists():
        upstream = cache.read_bytes()
    else:
        with urllib.request.urlopen(manifest["upstream_url"], timeout=60) as response:
            upstream = response.read()
    wheel = build(upstream, manifest)
    if not args.upstream_wheel and not cache.exists():
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_bytes(upstream)
    digest = hashlib.sha256(wheel).hexdigest()
    if digest != manifest["patched_sha256"] and not args.allow_unpinned_output:
        raise SystemExit(f"patched wheel SHA-256 mismatch: {digest}")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    dest = args.output_dir / f"pipecat_ai-{manifest['version']}-py3-none-any.whl"
    dest.write_bytes(wheel)
    print(f"{dest.name} sha256={digest}")


if __name__ == "__main__":
    main()
