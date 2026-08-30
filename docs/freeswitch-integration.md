# FreeSWITCH integration

The built-in `voice-gateway` can now control FreeSWITCH directly through the
inbound Event Socket. It does not expose port 8021 to users and it does not keep
SIP credentials in the application database.

## Control flow

```text
control-api -> voice-gateway HTTP -> FreeSWITCH ESL -> SIP gateway -> carrier
                                      |
                                      +-> status / media / recording callbacks
```

The ESL driver implements:

- `dial`: `bgapi originate ... &park()` with a platform correlation UUID;
- `speak`: `uuid_broadcast` using native FreeSWITCH TTS or an HTTP TTS media URI;
- `stop-speaking`: `uuid_break`;
- `transfer`: `uuid_transfer` to a mapped agent/default dialplan extension;
- `hangup`: `uuid_kill`;
- `/readyz`: a real `api status` probe;
- channel events: dialing, answer, busy/no-answer/failure/end callbacks;
- recording: `uuid_record` and recording metadata callback;
- optional media-bug command template for a selected streaming ASR module.

## FreeSWITCH prerequisites

1. Install a pinned FreeSWITCH release from the official source/packages. The
   upstream Docker example builds the development branch and should not be used
   unpinned in production.
2. Enable `mod_sofia`, `mod_event_socket`, the codecs required by the carrier,
   and the selected TTS/media-stream modules.
3. Copy and replace the example files under `deploy/freeswitch/` into the
   corresponding FreeSWITCH configuration directories.
4. Keep ESL port `8021/tcp` private and restrict it with an ACL/firewall.
5. Open the negotiated SIP and RTP ranges between FreeSWITCH and the carrier.
6. Create SIP/WebRTC users whose extension number matches the platform agent ID,
   or change `FREESWITCH_AGENT_EXTENSION_TEMPLATE` and the dialplan together.

The gateway name in FreeSWITCH must match `FREESWITCH_GATEWAY` (the examples use
`carrier`). Never commit the real SIP or ESL passwords.

## Application configuration

```env
TELEPHONY_PROVIDER=http
TELEPHONY_PROVIDER_ENDPOINT=http://voice-gateway:8002
VOICE_GATEWAY_DRIVER=freeswitch_esl

TELEPHONY_SERVICE_TOKEN=replace-with-strong-internal-token
TELEPHONY_WEBHOOK_TOKEN=replace-with-strong-webhook-token

FREESWITCH_ESL_HOST=freeswitch
FREESWITCH_ESL_PORT=8021
FREESWITCH_ESL_PASSWORD=replace-with-strong-esl-password
FREESWITCH_GATEWAY=carrier
FREESWITCH_CALLER_ID=REPLACE_APPROVED_CALLER_ID
FREESWITCH_DIALPLAN_CONTEXT=default
FREESWITCH_AGENT_EXTENSION_TEMPLATE=agent_{agent_id}
FREESWITCH_DEFAULT_HANDOFF_EXTENSION=handoff_default
```

Choose exactly one TTS route:

```env
# Native FreeSWITCH TTS module
FREESWITCH_TTS_ENGINE=REPLACE_ENGINE
FREESWITCH_TTS_VOICE=REPLACE_VOICE

# Or an internal HTTP service that returns {"media_uri":"..."}
FREESWITCH_TTS_HTTP_ENDPOINT=http://tts-adapter:8090/v1/synthesize
FREESWITCH_TTS_HTTP_TOKEN=replace-with-token
```

For streaming ASR, install and select a FreeSWITCH media-bug/WebSocket module,
then configure its actual API syntax. The gateway intentionally does not assume
that a non-core module is installed:

```env
FREESWITCH_MEDIA_START_COMMAND_TEMPLATE=REPLACE_WITH_MODULE_COMMAND
```

Available placeholders are `{uuid}`, `{call_id}`, `{speech_webhook_url}`,
`{media_webhook_url}`, `{asr_provider}` and `{language}`. The media service must
POST partial/final results to the supplied speech webhook using the documented
realtime voice contract.

## Read-only checks before dialing

From the FreeSWITCH console:

```text
sofia status gateway carrier
show registrations
status
```

From the application host:

```bash
curl --fail http://voice-gateway:8002/readyz
```

`/readyz` must become `503` if ESL/FreeSWITCH is unavailable.

## Real-line acceptance

Only after the carrier has approved the caller ID and the controlled test phone
is ready:

```bash
python scripts/real_voice_acceptance.py \
  --base-url http://127.0.0.1:8000 \
  --api-key REPLACE_API_KEY \
  --phone REPLACE_CONTROLLED_TEST_PHONE \
  --confirm-dial
```

Code tests and a mock ESL server do not prove carrier registration, bidirectional
audio, ASR/TTS quality, recording accessibility, WebRTC transfer, concurrency or
production readiness. Those items require the real SIP trunk and test handset.
