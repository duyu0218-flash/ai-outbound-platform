#!/usr/bin/env python3
"""Audit installed third-party packages, mapping only our verified patch to upstream.

Local versions are unknown to PyPI. Audit the exact upstream version without
resolving its old dependency tree; SBOMs retain the real installed patch version.
No vulnerability IDs or third-party packages are ignored.
"""

import importlib.metadata as metadata
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import tomllib

ROOT = Path(__file__).resolve().parents[1]


def requirements() -> list[str]:
    first_party = {}
    for service in ("backend", "agent", "voice_gateway", "recording_adapter"):
        project = tomllib.loads((ROOT / service / "pyproject.toml").read_text())["project"]
        first_party[project["name"]] = project["version"]
    manifest = json.loads((ROOT / "vendor/pipecat/manifest.json").read_text())
    result = []
    for dist in metadata.distributions():
        name, version = dist.metadata["Name"], dist.version
        if name in first_party:
            if version != first_party[name]:
                raise ValueError(f"unexpected first-party version: {name}=={version}")
            print(f"First-party project (audited via source/CI): {name}=={version}", flush=True)
            continue
        if name.lower().replace("_", "-") == "pipecat-ai" and "+" in version:
            subprocess.run([sys.executable, str(ROOT / "scripts/check-pipecat-distribution.py")], check=True)
            if version != manifest["version"]:
                raise ValueError("unapproved local Pipecat version")
            version = manifest["upstream_version"]
            print(f"Audit verified Pipecat patch against upstream advisory version {version}", flush=True)
        result.append(f"{name}=={version}")
    return sorted(set(result))


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="outbound-audit-") as temp:
        path = Path(temp) / "installed.txt"
        path.write_text("\n".join(requirements()) + "\n")
        subprocess.run([
            sys.executable, "-m", "pip_audit", "--strict", "--no-deps", "--disable-pip",
            "--requirement", str(path),
        ], check=True)


if __name__ == "__main__":
    main()
