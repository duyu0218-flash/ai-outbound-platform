#!/usr/bin/env sh
set -eu

target_dir=${1:?Usage: render-freeswitch-webrtc-config.sh /path/to/freeswitch-config}
control_url=${CONTROL_API_INTERNAL_URL:-http://127.0.0.1:8000}
: "${FREESWITCH_DIRECTORY_TOKEN:?FREESWITCH_DIRECTORY_TOKEN is required}"

case "$control_url" in
  *"&"*|*"<"*|*">"*|*"\""*|*"'"*) echo "CONTROL_API_INTERNAL_URL contains unsupported XML characters" >&2; exit 2 ;;
esac
case "$FREESWITCH_DIRECTORY_TOKEN" in
  *"&"*|*"<"*|*">"*|*"\""*|*"'"*|*"/"*) echo "FREESWITCH_DIRECTORY_TOKEN contains unsupported characters" >&2; exit 2 ;;
esac

mkdir -p "$target_dir/autoload_configs" "$target_dir/sip_profiles" "$target_dir/dialplan/default"
sed \
  -e "s|__CONTROL_API_URL__|$control_url|g" \
  -e "s|__DIRECTORY_TOKEN__|$FREESWITCH_DIRECTORY_TOKEN|g" \
  deploy/freeswitch/autoload_configs/xml_curl.conf.xml.example \
  > "$target_dir/autoload_configs/xml_curl.conf.xml"
cp deploy/freeswitch/sip_profiles/internal-webrtc.xml.example "$target_dir/sip_profiles/internal-webrtc.xml"
cp deploy/freeswitch/dialplan/default/agent_webrtc.xml "$target_dir/dialplan/default/agent_webrtc.xml"
