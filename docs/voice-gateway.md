# Voice gateway boundary

The control API does not open SIP sockets or mix RTP media. It calls the internal
`voice-gateway` contract, which can use a mock driver in development, proxy to
a PBX HTTP control adapter, or connect directly to FreeSWITCH Event Socket in
production.

## Contract

- `POST /v1/call/dial`
- `POST /v1/call/speak`
- `POST /v1/call/stop-speaking`
- `POST /v1/call/transfer`
- `POST /v1/call/hangup`
- `GET /readyz`

The gateway includes strict RTP v2 packet parsing/building and G.711 PCMA/PCMU codec
helpers. These are transport primitives, not a production media server.

## Production settings

```env
TELEPHONY_PROVIDER=http
TELEPHONY_PROVIDER_ENDPOINT=http://voice-gateway:8002
VOICE_GATEWAY_DRIVER=pbx_http
PBX_BASE_URL=http://freeswitch-adapter:8080
PBX_BEARER_TOKEN=replace-with-secret
RTP_PORT_START=20000
RTP_PORT_END=30000
```

Production startup rejects `VOICE_GATEWAY_DRIVER=mock`. The PBX/SIP carrier must
provide account registration, codec negotiation, NAT traversal, RTP port
exposure, recording, DTMF and transfer behavior. Capacity must be load-tested
end-to-end; adding this service alone does not establish 500-call concurrency.

For the direct FreeSWITCH path, replace the PBX settings above with:

```env
VOICE_GATEWAY_DRIVER=freeswitch_esl
FREESWITCH_ESL_HOST=freeswitch.internal
FREESWITCH_ESL_PORT=8021
FREESWITCH_ESL_PASSWORD=replace-with-a-strong-secret
FREESWITCH_GATEWAY=carrier
```

See [FreeSWITCH integration](freeswitch-integration.md) for the SIP gateway,
TTS, media streaming, recording, firewall and acceptance requirements.

## Flow version behavior

Canvas versions are immutable after publication. Campaigns can only bind a
published version belonging to the selected script template. Each generated
call inherits that version and its start node, so later draft edits do not alter
an active campaign.
