#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
source_dir="$repo/artifacts/voismart-poc/upstream"
revision=4c5f5b3000636edf6f5b08a482d62ec8d620bb06
if [ ! -d "$source_dir" ]; then
    git clone --branch v2.5.1 --depth 1 --recurse-submodules --shallow-submodules \
        https://github.com/VoiSmart/mod_openai_realtime.git "$source_dir"
fi
test "$(git -C "$source_dir" rev-parse HEAD)" = "$revision"
test -z "$(git -C "$source_dir" status --porcelain)"
test "$(git -C "$source_dir/libs/IXWebSocket" rev-parse HEAD)" = dfa10df5ae89697d5dad56e1845aee64d2334d70
docker build -f "$source_dir/Dockerfile.ci" --target builder \
    -t ai-freeswitch-voismart-builder:4c5f5b3 "$source_dir"
docker build -f "$source_dir/Dockerfile.ci" --target integration \
    -t ai-freeswitch-voismart-base:4c5f5b3 "$source_dir"
docker build -f "$repo/deploy/freeswitch-voismart/Dockerfile" \
    -t ai-freeswitch-voismart:2.5.1 "$source_dir"
printf '%s\n' 'Built local validation image; real SIP/cloud speech/production remain unverified.'
