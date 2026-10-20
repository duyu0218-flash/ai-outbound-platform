#!/bin/sh
set -eu
mono_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
node_bin=${NODE_BINARY:-}
if [ -z "$node_bin" ]; then node_bin=$(command -v node || true); fi
if [ -z "$node_bin" ] && [ -x "$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node" ]; then node_bin="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node"; fi
if [ -z "$node_bin" ]; then echo 'Node.js is required to build the Vue application (set NODE_BINARY).' >&2; exit 1; fi
PATH="$(dirname "$node_bin"):$PATH"
export PATH
if [ ! -f "$mono_dir/frontend/node_modules/vite/bin/vite.js" ]; then
  pnpm_bin=${PNPM_BINARY:-}
  if [ -z "$pnpm_bin" ]; then pnpm_bin=$(command -v pnpm || true); fi
  if [ -z "$pnpm_bin" ] && [ -x "$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/bin/fallback/pnpm" ]; then pnpm_bin="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/bin/fallback/pnpm"; fi
  if [ -z "$pnpm_bin" ]; then echo 'pnpm is required for a clean frontend build (set PNPM_BINARY).' >&2; exit 1; fi
  CI=true "$pnpm_bin" --dir "$mono_dir/frontend" install --frozen-lockfile
fi
# Remove only derived main output, so deleted classes and obsolete hashed assets cannot enter the next jar.
rm -rf -- "$mono_dir/target/classes"
cd "$mono_dir/frontend"
exec "$node_bin" node_modules/vite/bin/vite.js build
