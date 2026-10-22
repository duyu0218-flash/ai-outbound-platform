<script setup>
import { ref, reactive, watch, onMounted, onUnmounted, computed } from 'vue';
import { SimpleUser } from 'sip.js/lib/platform/web';
import { ElMessage } from 'element-plus';
import { request, session } from '../session';
import { useData } from '../data';
const props = defineProps({ activeCall: Object }),
  emit = defineEmits(['registered', 'update']),
  remoteAudio = ref(),
  config = ref(null),
  registration = ref('disconnected'),
  mediaState = ref('idle'),
  permission = ref('unknown'),
  inputs = ref([]),
  outputs = ref([]),
  inputDevice = ref(localStorage.getItem('agent-input-device') || ''),
  outputDevice = ref(localStorage.getItem('agent-output-device') || ''),
  muted = ref(false),
  held = ref(false),
  incoming = ref(false),
  busy = ref(false),
  lastError = ref(''),
  network = reactive({ network_quality: 'unknown' }),
  connectedAt = ref(null),
  elapsed = ref(0);
let phone = null,
  phoneConfig = null,
  mounted = true,
  connecting = false,
  renewTimer;
const controller = new AbortController(),
  timers = [];
const speech = useData(
    () =>
      props.activeCall?.id
        ? `/api/v1/calls/${props.activeCall.id}/speech-turns?final_only=true`
        : '',
    2000,
  ),
  analysis = useData(
    () => (props.activeCall?.id ? `/api/v1/calls/${props.activeCall.id}/analysis` : ''),
    5000,
  );
async function postStatus(patch = {}) {
  try {
    await request('/api/v1/agent/media/status', 'PUT', {
      registration_state: registration.value,
      media_state: mediaState.value,
      microphone_permission: permission.value,
      input_device_id: inputDevice.value,
      output_device_id: outputDevice.value,
      active_call_id: props.activeCall?.id,
      muted: muted.value,
      held: held.value,
      ...network,
      last_error: lastError.value,
      ...patch,
    });
  } catch {}
}
async function enumerate() {
  if (!navigator.mediaDevices?.enumerateDevices) {
    permission.value = 'unavailable';
    return;
  }
  const devices = await navigator.mediaDevices.enumerateDevices();
  inputs.value = devices
    .filter((v) => v.kind === 'audioinput')
    .map((v, i) => ({ value: v.deviceId, label: v.label || '麦克风 ' + (i + 1) }));
  outputs.value = devices
    .filter((v) => v.kind === 'audiooutput')
    .map((v, i) => ({ value: v.deviceId, label: v.label || '扬声器 ' + (i + 1) }));
}
async function disconnect() {
  const previous = phone;
  phone = null;
  if (previous) {
    try {
      await previous.unregister();
    } catch {}
    try {
      await previous.disconnect();
    } catch {}
  }
  if (mounted) {
    registration.value = 'disconnected';
    mediaState.value = 'idle';
    incoming.value = false;
    connectedAt.value = null;
  }
  await postStatus({
    registration_state: 'disconnected',
    media_state: 'idle',
    active_call_id: undefined,
  });
}
async function connect() {
  if (connecting) return;
  connecting = true;
  busy.value = true;
  lastError.value = '';
  registration.value = 'connecting';
  try {
    if (config.value && !config.value.enabled) throw new Error('服务器尚未启用浏览器软电话');
    if (!window.isSecureContext && !['localhost', '127.0.0.1'].includes(location.hostname))
      throw new Error('浏览器麦克风要求 HTTPS 安全环境');
    if (!navigator.mediaDevices?.getUserMedia) throw new Error('当前浏览器不支持麦克风访问');
    const probe = await navigator.mediaDevices.getUserMedia({
      audio: inputDevice.value ? { deviceId: { exact: inputDevice.value } } : true,
      video: false,
    });
    probe.getTracks().forEach((track) => track.stop());
    permission.value = 'granted';
    if ('Notification' in window && Notification.permission === 'default')
      void Notification.requestPermission();
    await enumerate();
    const next = await request('/api/v1/agent/webrtc/session', 'POST');
    config.value = next;
    if (!next.enabled) throw new Error('服务器尚未启用浏览器软电话');
    if (
      phone &&
      phoneConfig &&
      phoneConfig.wss_url === next.wss_url &&
      phoneConfig.sip_uri === next.sip_uri &&
      phoneConfig.authorization_password === next.authorization_password
    ) {
      await phone.register();
      phoneConfig = next;
      registration.value = 'registered';
      return;
    }
    if (phone?.session) {
      registration.value = 'registered';
      lastError.value = '注册配置已变化，将在本次通话结束后应用';
      return;
    }
    const delegate = {
      onServerConnect: () => (registration.value = 'connecting'),
      onServerDisconnect: (error) => {
        registration.value = 'disconnected';
        lastError.value = error?.message || 'SIP WSS连接已断开';
        postStatus({
          registration_state: 'disconnected',
          last_error: error?.message || 'SIP WSS disconnected',
        });
      },
      onRegistered: () => {
        registration.value = 'registered';
        lastError.value = '';
        postStatus({
          registration_state: 'registered',
          microphone_permission: 'granted',
          last_error: '',
        }).then(() => emit('registered'));
      },
      onUnregistered: () => (registration.value = 'disconnected'),
      onCallCreated: () => (mediaState.value = 'connecting'),
      onCallReceived: () => {
        incoming.value = true;
        mediaState.value = 'ringing';
        if ('Notification' in window && Notification.permission === 'granted')
          new Notification('AI外呼系统：转人工来电');
        postStatus({ media_state: 'ringing' });
      },
      onCallAnswered: () => {
        incoming.value = false;
        mediaState.value = 'active';
        connectedAt.value = Date.now();
        postStatus({ media_state: 'active' });
      },
      onCallHold: (value) => {
        held.value = value;
        mediaState.value = value ? 'held' : 'active';
        postStatus({ held: value, media_state: mediaState.value });
      },
      onCallHangup: () => {
        incoming.value = false;
        mediaState.value = 'ended';
        muted.value = false;
        held.value = false;
        connectedAt.value = null;
        postStatus({ media_state: 'ended', muted: false, held: false, active_call_id: undefined });
        emit('update');
      },
    };
    const constraints = inputDevice.value
      ? {
          deviceId: { exact: inputDevice.value },
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        }
      : true;
    const options = {
      aor: next.sip_uri,
      delegate,
      reconnectionAttempts: 5,
      reconnectionDelay: 3,
      sendDTMFUsingSessionDescriptionHandler: true,
      media: {
        constraints: { audio: constraints, video: false },
        remote: { audio: remoteAudio.value },
      },
      userAgentOptions: {
        authorizationUsername: next.authorization_username,
        authorizationPassword: next.authorization_password,
        displayName: next.extension,
        sessionDescriptionHandlerFactoryOptions: {
          peerConnectionConfiguration: { iceServers: next.ice_servers },
        },
      },
    };
    await disconnect();
    phone = new SimpleUser(next.wss_url, options);
    phoneConfig = next;
    await phone.connect();
    await phone.register();
  } catch (error) {
    registration.value = 'error';
    mediaState.value = 'error';
    lastError.value = error.message || '软电话连接失败';
    if (/permission|denied/i.test(lastError.value)) permission.value = 'denied';
    ElMessage.error(lastError.value);
    postStatus({ registration_state: 'error', media_state: 'error', last_error: lastError.value });
  } finally {
    connecting = false;
    busy.value = false;
  }
}
async function operate(kind) {
  try {
    busy.value = true;
    if (kind === 'answer') await phone?.answer();
    if (kind === 'decline') {
      await phone?.decline();
      incoming.value = false;
      mediaState.value = 'idle';
    }
    if (kind === 'hangup') {
      await phone?.hangup();
      if (props.activeCall?.id)
        await request(`/api/v1/calls/${props.activeCall.id}/hangup?reason=agent_browser`, 'POST');
    }
    if (kind === 'mute') {
      if (!phone) return;
      if (muted.value) phone.unmute();
      else phone.mute();
      muted.value = !muted.value;
    }
    if (kind === 'hold') {
      if (held.value) await phone?.unhold();
      else await phone?.hold();
    }
  } catch (error) {
    ElMessage.error(error.message);
  } finally {
    busy.value = false;
  }
}
watch(outputDevice, async (value) => {
  if (remoteAudio.value?.setSinkId && value)
    try {
      await remoteAudio.value.setSinkId(value);
    } catch {
      ElMessage.warning('无法切换扬声器设备');
    }
  if (value) localStorage.setItem('agent-output-device', value);
});
watch(inputDevice, (value) => {
  if (value) localStorage.setItem('agent-input-device', value);
});
watch(
  () => [config.value?.expires_at, registration.value],
  () => {
    clearTimeout(renewTimer);
    if (!config.value?.expires_at || registration.value !== 'registered') return;
    renewTimer = setTimeout(
      async () => {
        try {
          const next = await request('/api/v1/agent/webrtc/session', 'POST');
          if (phone) {
            if (
              !next.enabled ||
              phoneConfig?.authorization_password !== next.authorization_password ||
              phoneConfig?.wss_url !== next.wss_url ||
              phoneConfig?.sip_uri !== next.sip_uri
            )
              throw new Error('注册配置已变化，请在通话结束后重新注册');
            await phone.register();
          }
          if (!mounted) return;
          config.value = next;
          phoneConfig = next;
        } catch (error) {
          lastError.value = error.message || '凭据续期失败，将重试';
          if (config.value)
            config.value = {
              ...config.value,
              expires_at: new Date(Date.now() + 70000).toISOString(),
            };
        }
      },
      Math.max(10000, new Date(config.value.expires_at).getTime() - Date.now() - 60000),
    );
  },
);
async function collect() {
  try {
    const peer = phone?.session?.sessionDescriptionHandler?.peerConnection;
    if (!peer) return;
    let rtt, jitter, lost;
    const stats = await peer.getStats();
    stats.forEach((report) => {
      if (
        report.type === 'candidate-pair' &&
        report.state === 'succeeded' &&
        report.currentRoundTripTime !== undefined
      )
        rtt = Number(report.currentRoundTripTime) * 1000;
      if (report.type === 'inbound-rtp' && report.kind === 'audio') {
        if (report.jitter !== undefined) jitter = Number(report.jitter) * 1000;
        if (report.packetsLost !== undefined) lost = Number(report.packetsLost);
      }
    });
    Object.assign(network, {
      network_quality:
        rtt === undefined && jitter === undefined && lost === undefined
          ? 'unknown'
          : (rtt || 0) > 450 || (jitter || 0) > 80 || (lost || 0) > 20
            ? 'poor'
            : (rtt || 0) > 250 || (jitter || 0) > 40 || (lost || 0) > 5
              ? 'fair'
              : 'good',
      round_trip_time_ms: rtt,
      jitter_ms: jitter,
      packets_lost: lost,
    });
  } catch {}
}
async function events() {
  while (!controller.signal.aborted) {
    try {
      const response = await fetch('/api/v1/agent/events/stream', {
        headers: { Authorization: 'Bearer ' + session.token },
        signal: controller.signal,
      });
      if (!response.ok || !response.body) throw new Error('HTTP ' + response.status);
      const reader = response.body.getReader(),
        decoder = new TextDecoder();
      let buffer = '';
      while (!controller.signal.aborted) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';
        if (lines.some(Boolean)) emit('update');
      }
    } catch {
      if (!controller.signal.aborted) await new Promise((resolve) => setTimeout(resolve, 2000));
    }
  }
}
onMounted(() => {
  request('/api/v1/agent/webrtc/session', 'POST')
    .then((value) => {
      if (mounted) config.value = value;
    })
    .catch(() => {
      if (mounted) config.value = null;
    });
  navigator.mediaDevices?.addEventListener('devicechange', enumerate);
  timers.push(
    setInterval(postStatus, 15000),
    setInterval(
      () =>
        (elapsed.value = connectedAt.value
          ? Math.max(0, Math.floor((Date.now() - connectedAt.value) / 1000))
          : 0),
      1000,
    ),
    setInterval(collect, 5000),
  );
  events();
});
onUnmounted(() => {
  mounted = false;
  controller.abort();
  timers.forEach(clearInterval);
  clearTimeout(renewTimer);
  navigator.mediaDevices?.removeEventListener('devicechange', enumerate);
  disconnect();
});
const clock = computed(
  () =>
    `${String(Math.floor(elapsed.value / 60)).padStart(2, '0')}:${String(elapsed.value % 60).padStart(2, '0')}`,
);
async function dtmf(tone) {
  try {
    await phone?.sendDTMF(tone);
  } catch (error) {
    ElMessage.error(error.message);
  }
}
</script>
<template>
  <el-card
    ><template #header
      ><div class="actions">
        <strong>浏览器软电话</strong
        ><el-tag
          :type="
            registration === 'registered' ? 'success' : registration === 'error' ? 'danger' : 'info'
          "
          >{{ registration }}</el-tag
        ><el-button :disabled="!config?.enabled" :loading="busy" @click="connect">{{
          registration === 'registered' ? '重新注册' : '启用软电话'
        }}</el-button>
      </div></template
    ><audio ref="remoteAudio" autoplay playsinline /><el-alert
      v-if="!config?.enabled"
      type="warning"
      title="服务器尚未启用WebRTC"
      description="需要配置FreeSWITCH WSS、SIP域名和coturn后才能真实接听。"
    /><el-alert
      v-if="lastError"
      type="error"
      :title="lastError"
      closable
      @close="lastError = ''"
    /><el-descriptions :column="2"
      ><el-descriptions-item label="分机">{{ config?.extension || '-' }}</el-descriptions-item
      ><el-descriptions-item label="麦克风权限">{{ permission }}</el-descriptions-item
      ><el-descriptions-item label="媒体状态">{{ mediaState }}</el-descriptions-item
      ><el-descriptions-item label="通话时长">{{ clock }}</el-descriptions-item
      ><el-descriptions-item label="网络质量">{{ network.network_quality }}</el-descriptions-item
      ><el-descriptions-item label="RTT / 抖动 / 丢包"
        >{{ network.round_trip_time_ms ?? '-' }} / {{ network.jitter_ms ?? '-' }} /
        {{ network.packets_lost ?? '-' }}</el-descriptions-item
      ></el-descriptions
    >
    <div class="form-grid">
      <el-form-item label="麦克风"
        ><el-select
          v-model="inputDevice"
          :disabled="['active', 'ringing', 'held'].includes(mediaState)"
          @visible-change="(value) => value && enumerate()"
          ><el-option
            v-for="item in inputs"
            :key="item.value"
            :value="item.value"
            :label="item.label" /></el-select></el-form-item
      ><el-form-item label="扬声器"
        ><el-select v-model="outputDevice" @visible-change="(value) => value && enumerate()"
          ><el-option
            v-for="item in outputs"
            :key="item.value"
            :value="item.value"
            :label="item.label" /></el-select
      ></el-form-item>
    </div>
    <div class="actions">
      <el-button v-if="incoming" type="success" :loading="busy" @click="operate('answer')"
        >接听</el-button
      ><el-button v-if="incoming" type="danger" @click="operate('decline')">拒接</el-button
      ><el-button :disabled="!['active', 'held'].includes(mediaState)" @click="operate('mute')">{{
        muted ? '取消静音' : '静音'
      }}</el-button
      ><el-button :disabled="!['active', 'held'].includes(mediaState)" @click="operate('hold')">{{
        held ? '恢复' : '保持'
      }}</el-button
      ><el-button
        type="danger"
        :disabled="!['active', 'held', 'connecting', 'ringing'].includes(mediaState)"
        @click="operate('hangup')"
        >挂断</el-button
      ><el-button @click="disconnect">断开注册</el-button>
    </div>
    <div class="actions" style="margin-top: 12px">
      <el-button
        v-for="tone in ['1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#']"
        :key="tone"
        :disabled="mediaState !== 'active'"
        @click="dtmf(tone)"
        >{{ tone }}</el-button
      >
    </div>
    <h4>最近转写</h4>
    <div
      v-for="item in (speech.data.value || []).slice(-3)"
      :key="item.id"
      class="speech-turn"
      :class="item.speaker_role"
    >
      {{ item.speaker_role }}：{{ item.transcript }}
    </div>
    <p v-if="analysis.data.value">{{ analysis.data.value.summary }}</p></el-card
  >
</template>
