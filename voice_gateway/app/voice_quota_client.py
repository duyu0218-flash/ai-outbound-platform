"""Connection/request boundaries only: PCM frames never touch the permit ledger."""
import asyncio
from contextlib import asynccontextmanager
from uuid import uuid4
import httpx


class VoicePermitClient:
    def __init__(self, settings, session, epoch='', http=None):
        self.settings, self.session, self.epoch, self.http = settings, session, epoch, http

    @asynccontextmanager
    async def transport(self):
        if self.http is not None:
            yield self.http
        else:
            async with httpx.AsyncClient(timeout=self.settings.media_rpc_timeout_sec, trust_env=False, follow_redirects=False) as client:
                yield client

    async def command(self, permit_id, kind, action, confirmed=False):
        body = dict(permit_id=permit_id, kind=kind, action=action, confirmed=confirmed,
            call_id=self.session.call_id, attempt=self.session.metadata['attempt'],
            worker_id=self.settings.media_worker_id, epoch=self.epoch,
            session_id=self.session.session_id)
        # A retry preserves identity. Failure never opens a provider connection.
        async with self.transport() as client:
            for retry in range(3):
                try:
                    response = await client.post(self.settings.media_control_url.rstrip('/') + '/v1/internal/voice-permits',
                        headers={'Authorization': 'Bearer ' + self.settings.media_rpc_token}, json=body)
                    response.raise_for_status()
                    return
                except (httpx.TimeoutException, httpx.NetworkError):
                    if retry == 2:
                        raise
                    await asyncio.sleep(.05 * (retry + 1))

    async def acquire(self, kind):
        identity = uuid4().hex
        await self.command(identity, kind, 'acquire')
        return identity

    async def release(self, identity, kind, confirmed):
        await self.command(identity, kind, 'release', confirmed)


def protect_services(stt, tts, client):
    """Wrap concrete service seams, including automatic ASR reconnects."""
    original_connect, original_disconnect = stt._connect, stt._disconnect
    permit = None

    async def connect():
        nonlocal permit
        socket = getattr(stt, '_websocket', None)
        if permit and getattr(getattr(socket, 'state', None), 'name', '') == 'OPEN':
            return await original_connect()
        identity = await client.acquire('asr')
        permit = identity
        try:
            await original_connect()
        except BaseException:
            # The remote may have accepted a handshake whose ACK was lost.
            await client.release(identity, 'asr', False)
            raise

    async def disconnect():
        nonlocal permit
        identity, permit = permit, None
        socket = getattr(stt, '_websocket', None)
        confirmed = False
        try:
            await original_disconnect()
            completed = getattr(stt, '_completed', None)
            confirmed = bool(completed and completed.is_set())
            if client.settings.voice_quota_normal_close_releases:
                confirmed = confirmed or getattr(socket, 'close_code', None) in (1000, 1001)
        finally:
            if identity:
                await client.release(identity, 'asr', confirmed)

    # The SDK must not retry an ambiguous synthesis behind one permit.
    sdk = getattr(tts, '_client', None)
    if sdk is not None and hasattr(sdk, 'max_retries'):
        sdk.max_retries = 0
    original_tts = tts.run_tts
    async def run_tts(*args, **kwargs):
        from pipecat.frames.frames import ErrorFrame
        identity = await client.acquire('tts')
        completed, error = False, False
        try:
            async for frame in original_tts(*args, **kwargs):
                error = error or isinstance(frame, ErrorFrame)
                yield frame
            completed = not error
        finally:
            await client.release(identity, 'tts', completed)

    stt._connect, stt._disconnect, tts.run_tts = connect, disconnect, run_tts
    return stt, tts
