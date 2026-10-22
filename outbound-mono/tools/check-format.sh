#!/bin/sh
set -eu
mono_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
java_bin=java
if [ -n "${JAVA_HOME:-}" ]; then java_bin="$JAVA_HOME/bin/java"; fi
"$java_bin" "$mono_dir/tools/FormatMapperXml.java" --check "$mono_dir/src/main/resources/mapper"
node_bin=${NODE_BINARY:-}
if [ -z "$node_bin" ]; then node_bin=$(command -v node || true); fi
if [ -z "$node_bin" ] && [ -x "$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node" ]; then
  node_bin="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node"
fi
if [ -z "$node_bin" ]; then echo 'Node.js is required for format checks (set NODE_BINARY).' >&2; exit 1; fi
cd "$mono_dir/frontend"
exec "$node_bin" node_modules/prettier/bin/prettier.cjs --config .prettierrc.json --check \
  'src/**/*.{js,vue,css}' 'tests/*.mjs' '*.{html,mjs}' \
  '../src/main/resources/*.{yaml,xml}' '../deploy/**/*.xml' '../*.{xml,yml}' '../.mvn/*.xml'
