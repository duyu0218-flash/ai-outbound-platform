#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
# The approved precompiled native artifact is an input, as are JDK/Node images.
# No third-party language bindings or interpreter build toolchain is installed.
: "${FS_NATIVE_IMAGE:?Set an approved precompiled FreeSWITCH/VoiSmart native artifact image}"
docker build --build-arg "FS_NATIVE_IMAGE=$FS_NATIVE_IMAGE" \
  -f "$repo/deploy/freeswitch-voismart/Dockerfile" \
  -t ai-freeswitch-voismart-java:2.5.1 "$repo"
printf '%s\n' 'Built native PBX with Java bootstrap; real SIP/cloud/production require separate acceptance.'
