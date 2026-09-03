#!/usr/bin/env python3
"""Verify the narrow NLTK security-exception boundary used by Pipecat."""

from __future__ import annotations

import ast
import importlib.metadata
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ALLOWED_PIPECAT_IMPORTERS = {"pipecat/utils/string.py"}
BLOCKED_API_NAMES = {
    "AveragedPerceptron",
    "PerceptronTagger",
    "TransitionParser",
    "save_maxent_params",
}


def nltk_imports(path: Path) -> set[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    imports: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imports.update(alias.name for alias in node.names if alias.name == "nltk" or alias.name.startswith("nltk."))
        elif isinstance(node, ast.ImportFrom) and node.module:
            if node.module == "nltk" or node.module.startswith("nltk."):
                imports.add(node.module)
    return imports


def main() -> None:
    errors: list[str] = []

    for path in sorted((ROOT / "voice_gateway" / "app").rglob("*.py")):
        imports = nltk_imports(path)
        source = path.read_text(encoding="utf-8")
        blocked = sorted(name for name in BLOCKED_API_NAMES if name in source)
        if imports or blocked:
            errors.append(f"application NLTK boundary changed: {path.relative_to(ROOT)}")

    distribution = importlib.metadata.distribution("pipecat-ai")
    importers: set[str] = set()
    for entry in distribution.files or []:
        relative = entry.as_posix()
        if not relative.startswith("pipecat/") or not relative.endswith(".py"):
            continue
        path = Path(distribution.locate_file(entry))
        if nltk_imports(path):
            importers.add(relative)
            source = path.read_text(encoding="utf-8")
            blocked = sorted(name for name in BLOCKED_API_NAMES if name in source)
            if blocked:
                errors.append(f"Pipecat uses vulnerable NLTK APIs in {relative}: {', '.join(blocked)}")

    if importers != ALLOWED_PIPECAT_IMPORTERS:
        errors.append(
            "Pipecat NLTK import set changed: "
            f"expected {sorted(ALLOWED_PIPECAT_IMPORTERS)}, got {sorted(importers)}"
        )

    nltk_version = importlib.metadata.version("nltk")
    pipecat_version = importlib.metadata.version("pipecat-ai")
    if nltk_version != "3.10.3" or pipecat_version != "1.8.1":
        errors.append(
            "security exception pins changed: "
            f"nltk={nltk_version}, pipecat-ai={pipecat_version}"
        )

    if errors:
        raise SystemExit("\n".join(errors))
    print("NLTK advisory boundary: PASS")


if __name__ == "__main__":
    main()
