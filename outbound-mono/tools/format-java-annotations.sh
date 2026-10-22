#!/bin/sh
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
node_bin=${NODE_BINARY:-}
if [ -z "$node_bin" ]; then node_bin=$(command -v node || true); fi
if [ -z "$node_bin" ] && [ -x "$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node" ]; then
  node_bin="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node"
fi
if [ -z "$node_bin" ]; then echo 'Node.js is required for annotation formatting (set NODE_BINARY).' >&2; exit 1; fi
exec "$node_bin" "$script_dir/format-java-annotations.mjs"
