# Copyright (c) 2024-2026, Daily
# SPDX-License-Identifier: BSD-2-Clause
# Modified for ai-outbound: the rule-based sentence scanner needs no warm-up.

"""Compatibility entry point for the pipeline's background warm-up task."""


def warm_deferred_imports() -> None:
    """No-op: sentence boundaries require neither imports nor model downloads."""
