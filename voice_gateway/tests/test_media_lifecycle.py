import asyncio

import pytest

from app.esl import EslError
from app.freeswitch import CallBinding, FreeswitchEslDriver
from app.pipecat_pipeline import PipecatPipelineManager
from test_voismart import Esl, settings


async def until(predicate):
    async with asyncio.timeout(1):
        while not predicate():
            await asyncio.sleep(0)


def make_driver(**overrides):
    driver = FreeswitchEslDriver(settings(**overrides), client=Esl())
    binding = CallBinding(call_id='test', fs_uuid='test-uuid', status_webhook_url='status',
                          metadata={'recording_enabled': False}, voice_ai_pipeline='pipecat')
    driver.calls_by_id[binding.call_id] = binding
    driver.calls_by_uuid[binding.fs_uuid] = binding
    callbacks = []

    async def capture(url, payload):
        callbacks.append(payload)

    driver._post_json = capture
    return driver, binding, callbacks


@pytest.mark.parametrize('timeout', [0, -1, float('nan'), float('inf'), 301])
def test_media_connect_timeout_is_bounded(timeout):
    with pytest.raises(RuntimeError, match='PIPECAT_MEDIA_CONNECT_TIMEOUT_SEC'):
        settings(pipecat_media_connect_timeout_sec=timeout).validate_runtime()


def test_missing_media_connection_times_out_revokes_token_and_hangs_up():
    async def run():
        driver, binding, callbacks = make_driver(pipecat_media_connect_timeout_sec=0.01)
        driver._schedule_media(binding)
        await driver.media_tasks[binding.call_id]
        assert binding.media_failure_reason == 'MEDIA_CONNECT_TIMEOUT'
        assert not driver.pipecat_manager.sessions_by_call
        assert not driver.pipecat_manager.sessions_by_token
        assert driver.client.commands[-2:] == [
            'uuid_raw_audio_stream test-uuid stop', 'uuid_kill test-uuid NORMAL_TEMPORARY_FAILURE']
        assert callbacks[-1]['payload']['hangup_reason'] == 'MEDIA_CONNECT_TIMEOUT'
        await driver._handle_event({'Event-Name': 'CHANNEL_HANGUP_COMPLETE', 'Unique-ID': binding.fs_uuid,
                                    'Hangup-Cause': 'NORMAL_TEMPORARY_FAILURE'})
        assert not driver.calls_by_id and not driver.media_tasks
        assert len([c for c in callbacks if c['payload']['status'] == 'failed']) == 1
    asyncio.run(run())


@pytest.mark.parametrize('kind,reason', [('disconnect', 'MEDIA_DISCONNECTED'), ('error', 'MEDIA_MODULE_ERROR')])
def test_unexpected_module_failure_is_terminal(kind, reason):
    async def run():
        driver, binding, callbacks = make_driver()
        driver._schedule_media(binding)
        task = driver.media_tasks[binding.call_id]
        await until(lambda: binding.media_started)
        current = driver.pipecat_manager.sessions_by_call[binding.call_id]
        current.startup_complete.set()
        await driver._handle_event({'Event-Name': 'CUSTOM', 'Unique-ID': binding.fs_uuid,
                                    'Event-Subclass': f'mod_openai_audio_stream::{kind}'})
        await task
        assert binding.media_failure_reason == reason
        assert not driver.pipecat_manager.sessions_by_call
        assert callbacks[-1]['payload']['hangup_reason'] == reason
        assert driver.client.commands[-1] == 'uuid_kill test-uuid NORMAL_TEMPORARY_FAILURE'
    asyncio.run(run())


def test_transfer_cancels_supervision_and_ignores_late_media_and_answer_events():
    async def run():
        driver, binding, callbacks = make_driver()
        driver._schedule_media(binding)
        await until(lambda: binding.media_started)
        current = driver.pipecat_manager.sessions_by_call[binding.call_id]
        current.startup_complete.set()
        await driver.post('transfer', {'call_id': binding.call_id, 'target_group': 'agent:23'})
        commands = list(driver.client.commands)
        assert commands[-3:] == ['uuid_raw_audio_stream test-uuid stop', 'uuid_break test-uuid all',
                                'uuid_transfer test-uuid agent_23 XML default']
        assert current.closing and not driver.media_tasks
        assert not driver.pipecat_manager.sessions_by_call
        await driver._handle_event({'Event-Name': 'CUSTOM', 'Unique-ID': binding.fs_uuid,
                                    'Event-Subclass': 'mod_openai_audio_stream::disconnect'})
        await driver._handle_event({'Event-Name': 'CHANNEL_ANSWER', 'Unique-ID': binding.fs_uuid})
        assert driver.client.commands == commands
        assert not binding.media_failure_reason
        assert all(c['payload']['status'] != 'failed' for c in callbacks)
    asyncio.run(run())


@pytest.mark.parametrize('action', ['transfer', 'hangup'])
def test_cancelled_recording_notice_cannot_restart_recording_or_media(action):
    async def run():
        driver, binding, callbacks = make_driver()
        binding.metadata.update(recording_enabled=True, recording_notice=True)
        driver._schedule_media(binding)
        await until(lambda: binding.fs_uuid in driver.playback_waiters)
        waiter = driver.playback_waiters[binding.fs_uuid]
        await driver.post(action, {'call_id': binding.call_id, 'target_group': 'agent:23'})
        assert waiter.cancelled()
        assert not driver.playback_waiters and not driver.media_tasks
        await driver._handle_event({'Event-Name': 'CHANNEL_EXECUTE_COMPLETE', 'Unique-ID': binding.fs_uuid,
                                    'Application': 'playback'})
        assert not any('uuid_record' in c or 'uuid_raw_audio_stream' in c for c in driver.client.commands)
        assert not driver.pipecat_manager.sessions_by_call
        assert not callbacks
    asyncio.run(run())


def test_cancelled_start_response_still_stops_potentially_started_module():
    async def run():
        driver, binding, _ = make_driver()
        sent = asyncio.Event()
        original = driver.client.api

        async def delayed(command):
            result = await original(command)
            if ' start ws://' in command:
                sent.set()
                await asyncio.Event().wait()
            return result

        driver.client.api = delayed
        driver._schedule_media(binding)
        await sent.wait()
        await driver.post('hangup', {'call_id': binding.call_id})
        assert 'uuid_raw_audio_stream test-uuid stop' in driver.client.commands
        assert not driver.pipecat_manager.sessions_by_call and not driver.media_tasks
    asyncio.run(run())


def test_startup_failure_callback_does_not_expose_command_token():
    async def run():
        driver, binding, callbacks = make_driver()
        original = driver.client.api

        async def fail(command):
            if ' start ws://' in command:
                raise EslError(f'rejected command: {command}')
            return await original(command)

        driver.client.api = fail
        driver._schedule_media(binding)
        await driver.media_tasks[binding.call_id]
        assert callbacks[-1]['payload']['hangup_reason'] == 'MEDIA_STARTUP_FAILED'
        assert 'ws://' not in str(callbacks)
    asyncio.run(run())


def test_cancel_notice_during_slow_callback_also_cleans_waiter():
    async def run():
        driver, binding, _ = make_driver()
        binding.metadata.update(recording_enabled=True, recording_notice=True)

        async def slow(*args, **kwargs):
            await asyncio.Event().wait()

        driver._post_media = slow
        driver._schedule_media(binding)
        await until(lambda: binding.fs_uuid in driver.playback_waiters)
        waiter = driver.playback_waiters[binding.fs_uuid]
        await driver.post('hangup', {'call_id': binding.call_id})
        assert waiter.cancelled() and not driver.playback_waiters
        assert not driver.media_tasks
    asyncio.run(run())


def test_closing_session_does_not_accept_new_speech():
    async def run():
        manager = PipecatPipelineManager(settings())
        current = await manager.create_session(call_id='test', speech_webhook_url='', media_webhook_url='', metadata={})
        current.closing = True
        with pytest.raises(KeyError):
            await manager.speak('test', 'must not queue during transfer')
        assert not current.pending_speech
        await manager.close('test')
    asyncio.run(run())


def test_peer_hangup_precedes_media_teardown_and_is_not_a_media_failure():
    async def run():
        driver, binding, callbacks = make_driver()
        driver._schedule_media(binding)
        await until(lambda: binding.media_started)
        current = driver.pipecat_manager.sessions_by_call[binding.call_id]
        current.startup_complete.set()
        await driver._handle_event({'Event-Name': 'CHANNEL_HANGUP', 'Unique-ID': binding.fs_uuid})
        await driver._handle_event({'Event-Name': 'CUSTOM', 'Unique-ID': binding.fs_uuid,
                                    'Event-Subclass': 'mod_openai_audio_stream::disconnect'})
        await driver._handle_event({'Event-Name': 'CHANNEL_HANGUP_COMPLETE', 'Unique-ID': binding.fs_uuid,
                                    'Hangup-Cause': 'NORMAL_CLEARING'})
        assert current.closing and not current.media_error_code
        assert not binding.media_failure_reason and not driver.media_tasks
        assert not driver.calls_by_id and not driver.pipecat_manager.sessions_by_call
        assert callbacks[-1]['payload']['status'] == 'ended'
        assert not any(c.startswith('uuid_kill') for c in driver.client.commands)
    asyncio.run(run())


def test_media_socket_can_close_before_peer_hangup_event_arrives():
    async def run():
        driver, binding, callbacks = make_driver()
        original = driver.client.api

        async def gone(command):
            if command.startswith('uuid_exists'):
                return 'false'
            return await original(command)

        driver.client.api = gone
        driver._schedule_media(binding)
        task = driver.media_tasks[binding.call_id]
        await until(lambda: binding.media_started)
        current = driver.pipecat_manager.sessions_by_call[binding.call_id]
        current.startup_complete.set()
        await driver.pipecat_manager.handle_module_event(binding.call_id, 'disconnect')
        await task
        assert not binding.media_failure_reason and not current.media_error_code
        await driver._handle_event({'Event-Name': 'CHANNEL_HANGUP_COMPLETE', 'Unique-ID': binding.fs_uuid,
                                    'Hangup-Cause': 'NORMAL_CLEARING'})
        assert callbacks[-1]['payload']['status'] == 'ended'
    asyncio.run(run())


def test_close_during_worker_setup_cancels_socket_task_and_revokes_token():
    class Socket:
        async def accept(self):
            pass

    async def run():
        manager = PipecatPipelineManager(settings())
        current = await manager.create_session(call_id='test', speech_webhook_url='', media_webhook_url='', metadata={})
        started = asyncio.Event()

        async def setup(*args):
            started.set()
            await asyncio.Event().wait()

        manager._run_session = setup
        task = asyncio.create_task(manager.run_websocket(Socket(), current.token))
        await started.wait()
        await manager.close('test')
        assert task.done() and current.terminated.is_set()
        assert not current.media_error_code and current.closing
        assert not manager.sessions_by_call and not manager.sessions_by_token
    asyncio.run(run())


@pytest.mark.parametrize('action', ['speak', 'stop-speaking', 'transfer', 'hangup'])
@pytest.mark.parametrize('error,expected', [(KeyError('closed'), 404), (TimeoutError(), 504)])
def test_media_http_errors_are_explicit(monkeypatch, action, error, expected):
    from fastapi.testclient import TestClient
    from app import main

    async def fail(*args):
        raise error

    monkeypatch.setattr(main.driver, 'post', fail)
    with TestClient(main.app) as client:
        response = client.post(f'/v1/call/{action}', json={'call_id': 'test', 'text': 'hello'})
        assert response.status_code == expected
        assert 'detail' in response.json()
