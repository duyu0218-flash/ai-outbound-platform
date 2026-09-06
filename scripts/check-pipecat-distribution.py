#!/usr/bin/env python3
"""Fail closed on upstream/replaced wheels, NLTK dependencies, or NLTK imports."""

import ast
import hashlib
import importlib.metadata as metadata
import importlib.util
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def main() -> None:
    manifest = json.loads((ROOT / "vendor/pipecat/manifest.json").read_text())
    distribution = metadata.distribution("pipecat-ai")
    require(distribution.version == manifest["version"], "unapproved Pipecat version")
    require(importlib.util.find_spec("nltk") is None, "NLTK is importable")
    for dist in metadata.distributions():
        require(dist.metadata["Name"].lower().replace("_", "-") != "nltk", "NLTK installed")
        for requirement in dist.requires or []:
            require(not re.match(r"nltk(?:\b|\[)", requirement, re.I), f"NLTK dependency: {dist.metadata['Name']}")
    # Compare every installed package byte with the approved built wheel, not
    # just a self-reported version or RECORD. Detect site-packages tampering.
    wheels = list((ROOT / "artifacts/wheels").glob("*.whl"))
    if not wheels:
        wheels = list(Path("/opt/wheels").glob("*.whl"))
    wheels = [p for p in wheels if p.name == f"pipecat_ai-{manifest['version']}-py3-none-any.whl"]
    require(len(wheels) == 1, "build the approved wheel before checking the installation")
    wheel = wheels[0]
    require(hashlib.sha256(wheel.read_bytes()).hexdigest() == manifest["patched_sha256"], "wheel digest mismatch")
    with zipfile.ZipFile(wheel) as archive:
        expected_sources = {name for name in archive.namelist() if name.startswith("pipecat/") and name.endswith(".py")}
        package_dir = Path(distribution.locate_file("pipecat"))
        actual_sources = {"pipecat/" + path.relative_to(package_dir).as_posix() for path in package_dir.rglob("*.py")}
        require(actual_sources == expected_sources, "installed Pipecat source file set differs")
        for name in archive.namelist():
            if not name.startswith("pipecat/") and not name.endswith("/METADATA"):
                continue
            path = Path(distribution.locate_file(name))
            require(path.read_bytes() == archive.read(name), f"installed file differs: {name}")
            if name.endswith(".py"):
                tree = ast.parse(path.read_bytes())
                for node in ast.walk(tree):
                    names = [a.name for a in node.names] if isinstance(node, ast.Import) else (
                        [node.module or ""] if isinstance(node, ast.ImportFrom) else []
                    )
                    require(not any(n == "nltk" or n.startswith("nltk.") for n in names), name)
    print(f"Pipecat {distribution.version}: approved wheel, no NLTK dependency/import")


if __name__ == "__main__":
    main()
