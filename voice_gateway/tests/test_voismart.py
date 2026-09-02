import asyncio
import json
from types import SimpleNamespace

import pytest
from pipecat.frames.frames import (
    InterruptionFrame, OutputAudioRawFrame, OutputTransportMessageFrame,
    TTSStartedFrame, TTSStoppedFrame, UserStartedSpeakingFrame,
)
from pipecat.processors.frame_processor import FrameDirection

from app.config import Settings
from app.esl import EslError
from app.freeswitch import CallBinding, FreeswitchEslDriver, EVENT_NAMES
from app.pipecat_pipeline import RawPcmSerializer, PipecatPipelineManager, MediaStateWebhookProcessor, TranscriptWebhookProcessor


def settings(**overrides):
    return Settings(_env_file=None, voice_gateway_driver='freeswitch_esl', voice_ai_pipeline='pipecat',
                    freeswitch_gateway='carrier', freeswitch_tts_engine='flite', freeswitch_tts_voice='slt',
                    pipecat_version='1.8.1', pipecat_media_protocol='voismart',
                    pipecat_media_ws_base='ws://gateway/v1/pipecat/media', pipecat_openai_api_key='test', **overrides)


async def session(manager):
    return await manager.create_session(call_id='test', speech_webhook_url='', media_webhook_url='', metadata={})


def test_voismart_defaults_supply_module_command_without_changing_generic_validation():
    config = settings()
    config.validate_runtime()
    config.pipecat_media_protocol = 'raw_pcm'
    with pytest.raises(RuntimeError, match='START_COMMAND_TEMPLATE'):
        config.validate_runtime()
    config.pipecat_media_protocol = 'unknown'
    with pytest.raises(RuntimeError, match='PIPECAT_MEDIA_PROTOCOL'):
        config.validate_runtime()


def test_serializer_sends_raw_pcm_and_allowlisted_module_controls_only():
    async def run():
        serializer = RawPcmSerializer(8000, protocol='voismart')
        assert await serializer.serialize(OutputAudioRawFrame(b'\x01\x00', 8000, 1)) == b'\x01\x00'
        assert json.loads(await serializer.serialize(InterruptionFrame())) == {'type': 'input_audio_buffer.speech_started'}
        assert json.loads(await serializer.serialize(OutputTransportMessageFrame(message={'type': 'response.output_audio.done'}))) == {'type': 'response.output_audio.done'}
        assert await serializer.serialize(OutputTransportMessageFrame(message={'type': 'arbitrary-command'})) is None
        assert await RawPcmSerializer(8000).serialize(InterruptionFrame()) is None
        assert await serializer.deserialize('{"type":"untrusted"}') is None
        assert (await serializer.deserialize(b'\x01\x00')).audio == b'\x01\x00'
    asyncio.run(run())


def test_generation_end_queues_done_after_stop_without_premature_listening():
    async def run():
        manager = PipecatPipelineManager(settings())
        current = await session(manager)
        current.playback_id = 'utterance-1'
        processor = MediaStateWebhookProcessor(manager, current)
        states, frames = [], []

        async def media(_, state, **kwargs):
            states.append((state, kwargs))

        async def push(frame, direction):
            frames.append(frame)

        manager.post_media, processor.push_frame = media, push
        await processor.process_frame(TTSStartedFrame(), FrameDirection.DOWNSTREAM)
        await processor.process_frame(TTSStoppedFrame(), FrameDirection.DOWNSTREAM)
        assert not states and current.playback_id == 'utterance-1'
        assert isinstance(frames[-2], TTSStoppedFrame)
        assert frames[-1].message == {'type': 'response.output_audio.done'}
        await manager.handle_module_event('test', 'openai_speech_start', 10)
        await manager.handle_module_event('test', 'openai_speech_start', 10)  # duplicate
        await manager.handle_module_event('test', 'openai_speech_stop', 9)  # out of order
        assert current.playback_id == 'utterance-1'
        await manager.handle_module_event('test', 'openai_speech_stop', 11)
        assert [state for state, _ in states] == ['speaking', 'listening']
        assert states[0][1]['playback_id'] == 'utterance-1'
        assert current.playback_id is None
    asyncio.run(run())


def test_vad_speech_start_is_forwarded_as_real_transport_interruption():
    async def run():
        manager = PipecatPipelineManager(settings())
        processor = TranscriptWebhookProcessor(manager, await session(manager))
        frames = []

        async def push(frame, direction):
            frames.append(frame)

        processor.push_frame = push
        await processor.process_frame(UserStartedSpeakingFrame(), FrameDirection.DOWNSTREAM)
        assert isinstance(frames[0], InterruptionFrame)
        assert processor.user_is_speaking
    asyncio.run(run())


def test_interruption_reaches_transport_before_control_plane_callback():
    async def run():
        from pipecat.clocks.system_clock import SystemClock
        from pipecat.processors.frame_processor import FrameProcessorSetup
        from pipecat.utils.asyncio.task_manager import TaskManager
        manager = PipecatPipelineManager(settings())
        processor = MediaStateWebhookProcessor(manager, await session(manager))
        await processor.setup(FrameProcessorSetup(clock=SystemClock(),
                              task_manager=TaskManager(loop=asyncio.get_running_loop()), pipeline_worker=None))
        order = []

        async def push(frame, direction):
            assert isinstance(frame, InterruptionFrame)
            order.append('transport')

        async def media(*args, **kwargs):
            order.append('webhook')

        processor.push_frame, manager.post_media = push, media
        await processor.process_frame(InterruptionFrame(), FrameDirection.DOWNSTREAM)
        assert order == ['transport', 'webhook']
        await processor.cleanup()
    asyncio.run(run())


def test_overlapping_speech_is_rejected_until_actual_playout_drains():
    async def run():
        manager = PipecatPipelineManager(settings())
        await session(manager)
        await manager.speak('test', 'first')
        with pytest.raises(RuntimeError, match='busy'):
            await manager.speak('test', 'second')
        await manager.handle_module_event('test', 'openai_speech_stop', 1)
        assert await manager.speak('test', 'second')
    asyncio.run(run())


def test_interrupt_waits_for_module_clear_and_close_is_idempotent():
    async def run():
        manager = PipecatPipelineManager(settings())
        current = await session(manager)
        await manager.speak('test', 'first')
        current.module_speaking = True
        queued = []

        async def queue(frames):
            queued.extend(frames)
            await manager.handle_module_event('test', 'openai_speech_stop', 1)

        current.worker = SimpleNamespace(queue_frames=queue)
        await manager.interrupt('test')
        assert isinstance(queued[0], InterruptionFrame)
        assert not current.pending_speech and not current.interrupting
        await manager.close('test')
        await manager.close('test')
        assert not manager.sessions_by_token and not manager.sessions_by_call
    asyncio.run(run())


def test_failed_worker_setup_releases_reserved_token():
    class Socket:
        async def accept(self):
            pass

        async def close(self, **kwargs):
            pass

    async def run():
        manager = PipecatPipelineManager(settings())
        current = await session(manager)

        async def fail(*args):
            raise RuntimeError('provider setup failure')

        manager._run_session = fail
        with pytest.raises(RuntimeError, match='provider setup'):
            await manager.run_websocket(Socket(), current.token)
        assert not manager.sessions_by_call and not manager.sessions_by_token
    asyncio.run(run())


def test_media_socket_is_accepted_only_after_token_claim_and_cannot_be_reused():
    class Socket:
        accepted = False
        close_code = None

        async def accept(self):
            self.accepted = True

        async def close(self, code, reason):
            self.close_code = code

    async def run():
        manager = PipecatPipelineManager(settings())
        current = await session(manager)
        claimed, finish = asyncio.Event(), asyncio.Event()

        async def hold(socket, current):
            assert socket.accepted
            claimed.set()
            await finish.wait()

        manager._run_session = hold
        first, duplicate, unknown = Socket(), Socket(), Socket()
        task = asyncio.create_task(manager.run_websocket(first, current.token))
        await claimed.wait()
        await manager.run_websocket(duplicate, current.token)
        await manager.run_websocket(unknown, 'invalid-token')
        assert duplicate.close_code == 4409 and not duplicate.accepted
        assert unknown.close_code == 4404 and not unknown.accepted
        finish.set()
        await task
        reused = Socket()
        await manager.run_websocket(reused, current.token)
        assert reused.close_code == 4404 and not reused.accepted
    asyncio.run(run())


def test_playback_busy_maps_to_http_conflict(monkeypatch):
    from fastapi.testclient import TestClient
    from app import main
    from app.pipecat_pipeline import MediaPlaybackBusyError

    async def busy(*args):
        raise MediaPlaybackBusyError('media playback is busy')

    monkeypatch.setattr(main.driver, 'post', busy)
    with TestClient(main.app) as client:
        result = client.post('/v1/call/speak', json={'call_id': 'test', 'text': 'second'})
        assert result.status_code == 409


class Esl:
    def __init__(self):
        self.commands = []

    async def api(self, command):
        self.commands.append(command)
        return 'true' if command.startswith('module_exists') else '+OK'


def test_driver_initializes_write_clock_and_privacy_settings_and_routes_custom_events():
    async def run():
        config = settings()
        esl = Esl()
        manager = PipecatPipelineManager(config)
        driver = FreeswitchEslDriver(config, client=esl, pipecat_manager=manager)
        binding = CallBinding(call_id='test', fs_uuid='uuid-1', status_webhook_url='', voice_ai_pipeline='pipecat')
        driver.calls_by_id['test'] = binding
        driver.calls_by_uuid['uuid-1'] = binding
        await driver._start_pipecat_media(binding)
        await driver._start_pipecat_media(binding)
        assert len([x for x in esl.commands if 'silence_stream://' in x]) == 1
        assert esl.commands[:3] == [f'uuid_setvar uuid-1 {name} true' for name in ('STREAM_DISABLE_AUDIOFILES', 'STREAM_NO_RECONNECT', 'STREAM_SUPPRESS_LOG')]
        assert esl.commands[3].startswith('uuid_raw_audio_stream uuid-1 start ws://gateway/v1/pipecat/media/')
        assert esl.commands[3].endswith(' mono 8000 8000')
        assert 'CUSTOM' in EVENT_NAMES
        current = manager.sessions_by_call['test']
        await driver._handle_event({'Event-Name': 'CUSTOM', 'Event-Subclass': 'mod_openai_audio_stream::openai_speech_start', 'Unique-ID': 'uuid-1'})
        assert current.module_speaking
        await driver._stop_ai_media(binding)
        assert esl.commands[-1] == 'uuid_raw_audio_stream uuid-1 stop'
        assert not manager.sessions_by_call
    asyncio.run(run())


def test_clock_start_failure_stops_module_and_revokes_token():
    class FailingEsl(Esl):
        async def api(self, command):
            await super().api(command)
            if command.startswith('uuid_broadcast'):
                raise EslError('clock failed')
            return '+OK'

    async def run():
        driver = FreeswitchEslDriver(settings(), client=FailingEsl())
        binding = CallBinding(call_id='test', fs_uuid='uuid-1', status_webhook_url='', voice_ai_pipeline='pipecat')
        with pytest.raises(EslError, match='clock failed'):
            await driver._start_pipecat_media(binding)
        assert not driver.pipecat_manager.sessions_by_call
        assert not binding.media_started
        assert driver.client.commands[-1] == 'uuid_raw_audio_stream uuid-1 stop'
    asyncio.run(run())
