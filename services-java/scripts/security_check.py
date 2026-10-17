#!/usr/bin/env python3
"""Apply the same all-findings, source-bound security gate as the Java core."""
import importlib.util
import json
from pathlib import Path
import sys

module = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('core_security_gate', module.parent / 'backend-java/scripts/security_check.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
report = gate.evaluate(module)
print(json.dumps(report, indent=2))
sys.exit(0 if report['passed'] else 1)
