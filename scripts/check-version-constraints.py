#!/usr/bin/env python3
"""Validate the repository compatibility matrix and optional production pins."""

from __future__ import annotations

import argparse
import json
import re
import sys
import tomllib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MATRIX_PATH = ROOT / "compatibility-matrix.toml"
SHA_IMAGE = re.compile(r"^[^\s@]+@sha256:[0-9a-f]{64}$")
EXACT_VERSION = re.compile(r"^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$")
EXACT_PYTHON_DEPENDENCY = re.compile(r"^[A-Za-z0-9_.-]+(?:\[[^\]]+\])?==[^=\s]+$")


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def load_toml(path: Path) -> dict:
    with path.open("rb") as handle:
        return tomllib.load(handle)


def parse_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def validate_matrix(matrix: dict, errors: list[str]) -> None:
    if matrix.get("schema_version") != 1:
        fail(errors, "compatibility-matrix.toml schema_version must be 1")

    required_components = {
        "application",
        "python",
        "node",
        "pnpm",
        "setuptools",
        "postgresql",
        "redis",
        "freeswitch",
        "freeswitch_media_module",
        "coturn",
        "nginx",
        "pipecat",
        "github_actions",
    }
    components = matrix.get("components", {})
    missing = sorted(required_components - set(components))
    if missing:
        fail(errors, f"compatibility matrix is missing components: {', '.join(missing)}")

    policy = matrix.get("policy", {})
    for key in (
        "production_requires_immutable_images",
        "production_requires_exact_runtime_versions",
        "candidate_versions_must_pass_ci",
        "automatic_production_upgrade",
    ):
        if key not in policy:
            fail(errors, f"compatibility matrix policy is missing {key}")

    if policy.get("automatic_production_upgrade") is not False:
        fail(errors, "automatic_production_upgrade must remain false")


def validate_python_projects(matrix: dict, errors: list[str]) -> None:
    expected_app_version = matrix["components"]["application"]["constraint"].removeprefix("==")
    expected_python = matrix["components"]["python"]["constraint"].removeprefix("==").removesuffix(".*")
    expected_setuptools = matrix["components"]["setuptools"]["constraint"].removeprefix("==")
    for relative in ("backend/pyproject.toml", "agent/pyproject.toml", "voice_gateway/pyproject.toml"):
        path = ROOT / relative
        document = load_toml(path)
        project = document.get("project", {})
        build_requires = document.get("build-system", {}).get("requires", [])
        if build_requires != [f"setuptools=={expected_setuptools}"]:
            fail(errors, f"{relative} must pin build-system setuptools=={expected_setuptools}")
        if project.get("version") != expected_app_version:
            fail(errors, f"{relative} project.version must be {expected_app_version}")
        requires_python = str(project.get("requires-python", ""))
        if expected_python not in requires_python:
            fail(errors, f"{relative} requires-python must include {expected_python}")
        for dependency in project.get("dependencies", []):
            if not EXACT_PYTHON_DEPENDENCY.match(dependency):
                fail(errors, f"{relative} dependency is not exactly pinned: {dependency}")


def validate_frontend(matrix: dict, errors: list[str]) -> None:
    package = json.loads((ROOT / "frontend/package.json").read_text(encoding="utf-8"))
    expected_pnpm = matrix["components"]["pnpm"]["constraint"].removeprefix("==")
    if package.get("packageManager") != f"pnpm@{expected_pnpm}":
        fail(errors, f"frontend packageManager must be pnpm@{expected_pnpm}")
    if package.get("version") != matrix["components"]["application"]["constraint"].removeprefix("=="):
        fail(errors, "frontend version must match the application compatibility version")
    for section in ("dependencies", "devDependencies"):
        for name, version in package.get(section, {}).items():
            if not EXACT_VERSION.fullmatch(version):
                fail(errors, f"frontend {section} entry is not exactly pinned: {name}={version}")
    if not (ROOT / "frontend/pnpm-lock.yaml").is_file():
        fail(errors, "frontend/pnpm-lock.yaml is required")


def validate_repository_baseline(matrix: dict, errors: list[str]) -> None:
    ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    python_minor = matrix["components"]["python"]["constraint"].removeprefix("==").removesuffix(".*")
    node_major = matrix["components"]["node"]["constraint"].removeprefix("==").removesuffix(".*")
    if f'python-version: "{python_minor}"' not in ci:
        fail(errors, f"CI must test Python {python_minor}")
    if f'node-version: "{node_major}"' not in ci:
        fail(errors, f"CI must test Node {node_major}")
    if "python scripts/check-version-constraints.py" not in ci:
        fail(errors, "CI must run scripts/check-version-constraints.py")

    for relative in ("backend/Dockerfile", "agent/Dockerfile", "voice_gateway/Dockerfile"):
        dockerfile = (ROOT / relative).read_text(encoding="utf-8")
        if f"python:{python_minor}" not in dockerfile:
            fail(errors, f"{relative} must use the matrix Python {python_minor} baseline")
    backend_dockerfile = (ROOT / "backend/Dockerfile").read_text(encoding="utf-8")
    if f"node:{node_major}" not in backend_dockerfile:
        fail(errors, f"backend/Dockerfile must use the matrix Node {node_major} baseline")

    for variable in ("PYTHON_BASE_IMAGE", "NODE_BASE_IMAGE"):
        if variable not in backend_dockerfile:
            fail(errors, f"backend/Dockerfile must expose {variable} as a build argument")

    compose = (ROOT / "docker-compose.yml").read_text(encoding="utf-8")
    for component in ("postgresql", "redis"):
        image = matrix["components"][component]["development_image"]
        if image not in compose:
            fail(errors, f"docker-compose.yml does not contain matrix image {image}")

    webrtc_compose = (ROOT / "docker-compose.webrtc.yml").read_text(encoding="utf-8")
    for component in ("coturn", "nginx"):
        image = matrix["components"][component]["development_image"]
        if image not in webrtc_compose:
            fail(errors, f"docker-compose.webrtc.yml does not contain matrix image {image}")
    if "FREESWITCH_IMAGE" not in webrtc_compose:
        fail(errors, "docker-compose.webrtc.yml must require FREESWITCH_IMAGE")


def validate_production_env(matrix: dict, env_path: Path, errors: list[str]) -> None:
    env = parse_env(env_path)
    image_components = ("python", "node", "postgresql", "redis", "freeswitch", "coturn", "nginx")
    for component_name in image_components:
        component = matrix["components"][component_name]
        variable = component["production_image_env"]
        value = env.get(variable, "")
        if not SHA_IMAGE.fullmatch(value):
            fail(errors, f"{variable} must use an immutable image digest (repository@sha256:<64 hex>)")

    freeswitch_version_var = matrix["components"]["freeswitch"]["runtime_version_env"]
    freeswitch_version = env.get(freeswitch_version_var, "")
    if not EXACT_VERSION.fullmatch(freeswitch_version):
        fail(errors, f"{freeswitch_version_var} must be an exact runtime version such as 1.10.12")

    if env.get("FREESWITCH_MEDIA_START_COMMAND_TEMPLATE", ""):
        media_version_var = matrix["components"]["freeswitch_media_module"]["runtime_version_env"]
        if not EXACT_VERSION.fullmatch(env.get(media_version_var, "")):
            fail(errors, f"{media_version_var} is required when a FreeSWITCH media module is enabled")

    pipecat_version = env.get(matrix["components"]["pipecat"]["runtime_version_env"], "")
    if pipecat_version and not EXACT_VERSION.fullmatch(pipecat_version):
        fail(errors, "PIPECAT_VERSION must be an exact version when Pipecat is enabled")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--production-env",
        type=Path,
        help="also validate immutable production image references and runtime versions",
    )
    args = parser.parse_args()

    errors: list[str] = []
    matrix = load_toml(MATRIX_PATH)
    validate_matrix(matrix, errors)
    validate_python_projects(matrix, errors)
    validate_frontend(matrix, errors)
    validate_repository_baseline(matrix, errors)
    if args.production_env:
        validate_production_env(matrix, args.production_env, errors)

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("Version constraints and compatibility matrix: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
