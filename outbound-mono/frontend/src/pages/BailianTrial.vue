<script setup>
import { ref, computed, onUnmounted } from 'vue';
import { request, download, session } from '../session';
import { useData } from '../data';
import { formatDate } from '../api';
import { ElMessage } from 'element-plus';
const base = '/api/v1/admin/voice-ai/bailian';
const reviewMode = ref('native'),
  historyMode = ref('all'),
  prices = ref({});
const ready = useData(() => base + '/readiness?review_mode=' + reviewMode.value),
  history = useData(base + '/trials');
const configuration = useData('/api/v1/admin/voice-ai/configuration');
const contacts = useData('/api/v1/contacts?page=1&size=100');
const scripts = useData('/api/v1/script-templates?page=1&size=100');
const keys = [
  'DASHSCOPE_API_KEY',
  'VOICE_AI_BAILIAN_WORKSPACE_ID',
  'VOICE_AI_BAILIAN_APP_ID',
  'VOICE_AI_BAILIAN_APP_REVISION',
  'VOICE_AI_BAILIAN_VOICE',
  'VOICE_AI_BAILIAN_TOOL_API_KEY',
];
const labels = [
  '百炼 API Key',
  'Workspace ID',
  'App ID',
  '已发布应用版本标记',
  '音色 ID（留空沿用应用）',
  '业务工具 X-API-KEY',
];
const fields = computed(() =>
  keys.map((key, i) => ({
    ...configuration.data.value?.fields?.find((f) => f.key === key),
    key,
    label: labels[i],
  })),
);
const changes = ref({}),
  busy = ref(false),
  error = ref(''),
  contact = ref(),
  template = ref(),
  consent = ref(false),
  confirmed = ref(false);
const script = ref(
  '你是业务回访助手。请先说明这是平台内部测试，再询问对服务的意向和方便跟进的时间。通过 business_context 获取业务资料和用户转写证据序号，再调用 save_intent 登记意向草稿。保存成功后说明等待人工确认，不承诺已完成真实预约。用户拒绝时礼貌结束。',
);
const current = ref(null),
  events = ref([]),
  state = ref('未连接'),
  connected = ref(false),
  recording = ref(false),
  typed = ref(''),
  intent = ref('uncertain'),
  note = ref(''),
  next = ref('');
let socket,
  context,
  capture,
  stream,
  processor,
  inputSource,
  silent,
  sources = new Set(),
  playbackEnd = 0,
  playbackEpoch = -1,
  doneEpoch = -1,
  startedEpoch = -1,
  disposed = false,
  recordTimer,
  flushCapture = () => {},
  minimumEpoch = 0,
  blockedPlayback = false;
const profileOf = (row) => JSON.parse(row?.profile_json || '{}');
const metricsOf = (row) => JSON.parse(row?.metrics_json || '{}');
const modeOf = (row) => profileOf(row).review_mode || 'secondary_review';
const modeLabel = (mode) => (mode === 'native' ? '百炼原生' : '二次审核对照');
const currentMode = computed(() => modeOf(current.value));
const measured = computed(() => metricsOf(current.value));
const filteredHistory = computed(() =>
  (history.data.value || []).filter(
    (row) => historyMode.value === 'all' || modeOf(row) === historyMode.value,
  ),
);
const number = (v) => (v == null ? '未测得' : Number(v).toFixed(2));
const money = (v) => (v == null ? '未估算' : Number(v).toFixed(6) + ' 元');
const rateFields = [
  { key: 'suite_per_1000_turns', label: '套件估算单价（元/千轮）' },
  { key: 'asr_per_minute', label: '额外 ASR 单价（元/分钟）' },
  { key: 'review_per_10000_calls', label: '二次审核单价（元/万次）' },
];
const epoch = session.epoch;
const valid = () => !disposed && epoch === session.epoch;
const dialogue = computed(() =>
  events.value.filter((e) =>
    ['user_text', 'assistant_text', 'error', 'intent_saved'].includes(e.type),
  ),
);
const active = computed(
  () => connected.value || ['active', 'created'].includes(current.value?.status),
);
function send(value) {
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(value));
}
function applyResult(row) {
  const result = JSON.parse(row.result_json || '{}');
  intent.value = result.intent || 'uncertain';
  note.value = result.note || '';
  next.value = result.next_at || '';
  confirmed.value = false;
}
async function run(action) {
  busy.value = true;
  error.value = '';
  try {
    await action();
  } catch (e) {
    if (valid()) error.value = e.message;
  } finally {
    if (valid()) busy.value = false;
  }
}
async function saveConfig() {
  await run(async () => {
    await request('/api/v1/admin/voice-ai/configuration', 'PUT', { changes: changes.value });
    changes.value = {};
    await Promise.all([configuration.load(), ready.load()]);
    ElMessage.success('配置已加密保存');
  });
}
function chooseScript() {
  const row = (scripts.data.value || []).find((s) => s.id === template.value);
  if (row) script.value = row.content;
}
async function create() {
  await run(async () => {
    if (!contact.value || !script.value.trim() || !consent.value)
      throw new Error('请选择测试客户、填写话术并确认试聊授权');
    context ||= new AudioContext({ sampleRate: 24000 });
    await context.resume();
    const result = await request(base + '/trials', 'POST', {
      client_key: crypto.randomUUID(),
      contact_id: contact.value,
      script: script.value,
      consent: true,
      review_mode: reviewMode.value,
      prices: Object.fromEntries(
        Object.entries(prices.value)
          .filter(([, v]) => v !== '' && v != null)
          .map(([k, v]) => [k, Number(v)]),
      ),
    });
    if (!valid()) return;
    current.value = await request(base + '/trials/' + result.id);
    events.value = [];
    applyResult(current.value);
    if (!result.ticket) throw new Error('该请求已处理，请查看历史后重新创建试聊');
    state.value = '连接中';
    minimumEpoch = 0;
    blockedPlayback = false;
    socket = new WebSocket(
      `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/api/v1/voice-trial/socket`,
    );
    socket.onopen = () => {
      if (!valid() || current.value?.id !== result.id) return;
      connected.value = true;
      send({ type: 'attach', id: result.id, ticket: result.ticket });
    };
    socket.onmessage = (e) => {
      if (!valid() || current.value?.id !== result.id) return;
      const event = JSON.parse(e.data);
      if (event.type === 'metrics') {
        current.value.metrics_json = JSON.stringify(event.metrics);
        return;
      }
      if (event.type === 'audio') {
        play(event);
        return;
      }
      if (event.type === 'clear_audio') {
        minimumEpoch = Math.max(minimumEpoch, event.epoch);
        blockedPlayback = true;
        clearAudio();
        return;
      }
      if (event.type === 'response_started' && event.epoch >= minimumEpoch) blockedPlayback = false;
      if (event.type === 'state') state.value = event.state;
      if (event.type === 'response_done') {
        doneEpoch = event.epoch;
        finishPlayback();
      }
      if (event.type === 'error') error.value = event.message;
      if (event.type === 'intent_saved') {
        intent.value = event.result.intent;
        note.value = event.result.note;
        next.value = event.result.next_at;
      }
      if (event.type === 'partial') return;
      if (event.type === 'closed') {
        connected.value = false;
        state.value = event.status;
        stopCapture();
        clearAudio();
        load(result.id);
      }
      events.value.push(event);
    };
    socket.onerror = () => {
      if (!valid() || current.value?.id !== result.id) return;
      error.value = '试聊连接失败，请检查网络并刷新历史';
    };
    socket.onclose = () => {
      if (!valid() || current.value?.id !== result.id) return;
      connected.value = false;
      stopCapture();
      clearAudio();
      if (valid()) load(result.id);
    };
    await history.load();
  });
}
function clearAudio() {
  for (const source of sources) {
    source.onended = null;
    try {
      source.stop();
    } catch {}
  }
  sources.clear();
  playbackEnd = 0;
  doneEpoch = -1;
  playbackEpoch = -1;
  startedEpoch = -1;
}
function finishPlayback() {
  if (doneEpoch === playbackEpoch && sources.size === 0 && doneEpoch >= 0) {
    send({ type: 'played_end', epoch: doneEpoch });
    doneEpoch = -1;
  }
}
function play(event) {
  if (blockedPlayback || event.epoch < minimumEpoch) return;
  if (!context || context.state !== 'running') {
    error.value = '浏览器未允许音频播放，请结束试聊后重试';
    end();
    return;
  }
  if (playbackEpoch !== event.epoch) {
    clearAudio();
    playbackEpoch = event.epoch;
  }
  const raw = Uint8Array.from(atob(event.pcm), (c) => c.charCodeAt(0)),
    view = new DataView(raw.buffer),
    buffer = context.createBuffer(1, raw.length / 2, event.rate),
    samples = buffer.getChannelData(0);
  for (let i = 0; i < samples.length; i++) samples[i] = view.getInt16(i * 2, true) / 32768;
  const source = context.createBufferSource();
  source.buffer = buffer;
  source.connect(context.destination);
  sources.add(source);
  const start = Math.max(context.currentTime + 0.015, playbackEnd);
  playbackEnd = start + buffer.duration;
  source.onended = () => {
    sources.delete(source);
    finishPlayback();
  };
  source.start(start);
  if (startedEpoch !== event.epoch) {
    startedEpoch = event.epoch;
    send({ type: 'played_start', epoch: event.epoch });
  }
}
async function begin() {
  await run(async () => {
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
    });
    if (!valid() || !connected.value) {
      stopCapture();
      return;
    }
    capture = new AudioContext({ sampleRate: 16000 });
    await capture.resume();
    inputSource = capture.createMediaStreamSource(stream);
    processor = capture.createScriptProcessor(4096, 1, 1);
    silent = capture.createGain();
    silent.gain.value = 0;
    let queue = [],
      position = 0;
    flushCapture = () => {
      if (!queue.length || socket?.readyState !== WebSocket.OPEN) return;
      const bytes = new ArrayBuffer(queue.length * 2),
        view = new DataView(bytes);
      queue.splice(0).forEach((sample, i) => view.setInt16(i * 2, sample, true));
      socket.send(bytes);
    };
    send({ type: 'begin' });
    recording.value = true;
    processor.onaudioprocess = (e) => {
      if (!recording.value || !socket || socket.readyState !== WebSocket.OPEN) return;
      const floats = e.inputBuffer.getChannelData(0);
      for (let i = position; i < floats.length; i += capture.sampleRate / 16000)
        queue.push(Math.max(-32768, Math.min(32767, Math.round(floats[Math.floor(i)] * 32768))));
      position =
        position +
        Math.ceil((floats.length - position) / (capture.sampleRate / 16000)) *
          (capture.sampleRate / 16000) -
        floats.length;
      while (queue.length >= 1600) {
        if (socket.bufferedAmount > 64000) {
          error.value = '音频上传积压，试聊已停止';
          end();
          return;
        }
        const bytes = new ArrayBuffer(3200),
          v = new DataView(bytes);
        queue.splice(0, 1600).forEach((sample, i) => v.setInt16(i * 2, sample, true));
        socket.send(bytes);
      }
    };
    inputSource.connect(processor);
    processor.connect(silent);
    silent.connect(capture.destination);
    recordTimer = setTimeout(finishSpeech, 29000);
  });
  if (!recording.value) stopCapture();
}
function stopCapture() {
  clearTimeout(recordTimer);
  flushCapture = () => {};
  recording.value = false;
  stream?.getTracks().forEach((t) => t.stop());
  stream = null;
  if (processor) {
    processor.onaudioprocess = null;
    processor.disconnect();
    processor = null;
  }
  inputSource?.disconnect();
  inputSource = null;
  silent?.disconnect();
  silent = null;
  capture?.close();
  capture = null;
}
function finishSpeech() {
  flushCapture();
  stopCapture();
  send({ type: 'end' });
}
function interrupt() {
  blockedPlayback = true;
  stopCapture();
  clearAudio();
  send({ type: 'interrupt' });
  state.value = '等待重新收音';
}
function prompt() {
  if (!typed.value.trim()) return;
  send({ type: 'prompt', text: typed.value });
  typed.value = '';
  state.value = 'Thinking';
}
async function end() {
  blockedPlayback = true;
  stopCapture();
  clearAudio();
  send({ type: 'close' });
  if (current.value)
    await run(async () => {
      await request(base + '/trials/' + current.value.id + '/end', 'POST', {});
      await load(current.value.id);
    });
  socket?.close();
  connected.value = false;
}
async function load(id) {
  try {
    const row = await request(base + '/trials/' + id);
    if (!valid()) return;
    current.value = row;
    events.value = JSON.parse(row.events_json || '[]');
    applyResult(row);
    await history.load();
  } catch (e) {
    if (valid()) error.value = e.message;
  }
}
async function publish() {
  await run(async () => {
    await request(base + '/trials/' + current.value.id + '/publish', 'POST', {
      intent: intent.value,
      note: note.value,
      next_at: next.value,
      confirmed: confirmed.value,
    });
    await load(current.value.id);
    ElMessage.success('已写入测试客户的线索跟进记录');
  });
}
onUnmounted(() => {
  disposed = true;
  stopCapture();
  clearAudio();
  send({ type: 'close' });
  socket?.close();
  context?.close();
});
</script>
<template>
  <section class="bailian-trial">
    <div class="page-head">
      <h1>百炼业务试聊</h1>
      <el-button @click="$router.push('/admin/voice-ai')">返回语音方案对比</el-button>
    </div>
    <el-alert
      title="平台内部试聊：不会创建拨号任务或开启真实外呼。语音使用点击开始/结束说话，支持手动打断。"
      type="info"
      :closable="false"
    />
    <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <el-alert v-if="ready.error.value" :title="ready.error.value" type="error" :closable="false" />
    <el-collapse
      ><el-collapse-item title="百炼账号与应用配置" name="config">
        <el-form label-position="top"
          ><el-form-item v-for="field in fields" :key="field.key" :label="field.label">
            <el-input
              :model-value="
                Object.hasOwn(changes, field.key)
                  ? changes[field.key]
                  : field.secret
                    ? ''
                    : field.value
              "
              :type="field.secret ? 'password' : 'text'"
              :placeholder="field.configured ? '已配置，留空保持原值' : '未配置'"
              autocomplete="off"
              @update:model-value="
                (v) => {
                  if (v) changes[field.key] = v;
                  else delete changes[field.key];
                }
              "
            />
            <el-button v-if="field.configured" link type="danger" @click="changes[field.key] = ''"
              >清空此项</el-button
            > </el-form-item
          ><el-button
            type="primary"
            :loading="busy"
            :disabled="!configuration.data.value?.writable || !Object.keys(changes).length"
            @click="saveConfig"
            >加密保存百炼配置</el-button
          ></el-form
        >
        <p>
          应用提示词须引用 business_script。工具须透传 trial_id、trial_token，并配置业务回调地址和
          X-API-KEY。仅二次审核对照模式需要服务端配置独立输出识别与内容审核。
        </p>
      </el-collapse-item></el-collapse
    >
    <p v-if="!ready.data.value?.configured">
      待配置：{{ ready.data.value?.missing?.join('、') || '正在检查' }}
    </p>
    <p v-else>配置齐全，真实百炼权限和效果仍须联调。运行会产生供应商费用。</p>
    <el-card
      ><template #header>创建业务试聊</template>
      <el-form label-position="top">
        <el-form-item label="试聊方案">
          <el-radio-group v-model="reviewMode" :disabled="active" aria-label="试聊方案">
            <el-radio value="native">百炼原生（默认）</el-radio>
            <el-radio value="secondary_review">独立 ASR + 二次审核（对照）</el-radio>
          </el-radio-group>
        </el-form-item>
        <p v-if="reviewMode === 'native'">
          直接播放百炼返回的音频，不调用平台额外 ASR
          或二次内容审核；供应商原生审核范围仍待真实联调确认。
        </p>
        <p v-else>对照模式会额外识别输出音频，完整短句审核通过后播放；增加的调用和等待单独统计。</p>
        <el-collapse
          ><el-collapse-item title="可选：填写估算单价">
            <p>
              单价和方案在创建时固定，空白表示未估算。套件按实际选择的应用组合填写每千轮估算价，实际收费仍以账单为准。
            </p>
            <el-form-item v-for="field in rateFields" :key="field.key" :label="field.label">
              <el-input
                v-model="prices[field.key]"
                :aria-label="field.label"
                :disabled="
                  active || (reviewMode === 'native' && field.key !== 'suite_per_1000_turns')
                "
                placeholder="留空不估算"
              />
            </el-form-item> </el-collapse-item
        ></el-collapse>
        <el-form-item label="测试客户"
          ><el-select v-model="contact" filterable aria-label="测试客户" :disabled="active"
            ><el-option
              v-for="c in contacts.data.value || []"
              :key="c.id"
              :label="`${c.name || '客户'} #${c.id}`"
              :value="c.id" /></el-select
          ><el-button link @click="$router.push('/admin/contacts')"
            >管理测试客户</el-button
          ></el-form-item
        >
        <el-form-item label="引用现有话术"
          ><el-select
            v-model="template"
            clearable
            :disabled="active"
            aria-label="引用现有话术"
            @change="chooseScript"
            ><el-option
              v-for="s in scripts.data.value || []"
              :key="s.id"
              :label="s.name"
              :value="s.id" /></el-select
        ></el-form-item>
        <el-form-item label="本次业务话术"
          ><el-input
            v-model="script"
            type="textarea"
            :rows="5"
            maxlength="12000"
            :disabled="active"
            aria-label="本次业务话术"
        /></el-form-item>
        <el-checkbox v-model="consent" :disabled="active"
          >我已选择测试客户，并授权本次付费供应商试聊</el-checkbox
        >
        <div>
          <el-button
            type="primary"
            :loading="busy"
            :disabled="active || !ready.data.value?.configured || !consent"
            @click="create"
            >创建并连接试聊</el-button
          >
        </div>
      </el-form>
    </el-card>
    <el-card v-if="current"
      ><template #header
        >试聊 {{ current.id }} · {{ modeLabel(currentMode) }} · {{ state }} /
        {{ current.status }}</template
      >
      <el-space wrap>
        <el-button :disabled="!connected || recording || state !== 'Listening'" @click="begin"
          >开始说话</el-button
        >
        <el-button :disabled="!recording" @click="finishSpeech">结束说话</el-button>
        <el-button :disabled="!connected" @click="interrupt">打断回复</el-button>
        <el-button type="danger" :disabled="!active" @click="end">结束试聊</el-button>
        <el-button :disabled="active" @click="load(current.id)">刷新记录</el-button>
      </el-space>
      <p>
        每次收音最长 30 秒，会话最长 5
        分钟。输入录音与输出分开保存；输出标记当前固定方案。播放回执来自浏览器，不等于设备扬声器或电话端实测。
      </p>
      <el-input
        v-model="typed"
        aria-label="文字试聊"
        placeholder="可输入文字测试业务逻辑；不计为语音识别验收"
        maxlength="2000"
        :disabled="!connected"
        @keyup.enter="prompt"
        ><template #append
          ><el-button :disabled="state !== 'Listening' || !connected" @click="prompt"
            >发送文字</el-button
          ></template
        ></el-input
      >
      <ol class="dialogue">
        <li v-for="(e, i) in dialogue" :key="i">
          <strong>{{
            e.type === 'user_text'
              ? '用户'
              : e.type === 'assistant_text'
                ? (e.reviewed ?? currentMode === 'secondary_review')
                  ? '助手（二次审核通过）'
                  : '助手（百炼原生）'
                : e.type === 'intent_saved'
                  ? '业务草稿'
                  : '错误'
          }}</strong>
          {{ e.text || e.message || e.result?.note }}
        </li>
      </ol>
      <div class="trial-metrics" data-testid="trial-metrics">
        <h2>本次成本与延时 · {{ modeLabel(currentMode) }}</h2>
        <p v-if="!measured.version">本记录尚无分项测量；旧版记录不能按零费用计算。</p>
        <template v-else>
          <p>
            观测回复轮数 {{ measured.usage.suite_response_turns }}；套件输入/输出音频
            {{ number(measured.usage.suite_input_audio_seconds) }} /
            {{ number(measured.usage.suite_output_audio_seconds) }} 秒。
          </p>
          <p>
            额外 ASR 音频 {{ number(measured.usage.secondary_asr_audio_seconds) }} 秒；二次审核请求
            {{ measured.usage.secondary_review_requests }} 次（取得结果
            {{ measured.usage.secondary_review_results }} 次）。
          </p>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="套件估算费用">{{
              money(measured.cost.suite_estimate)
            }}</el-descriptions-item>
            <el-descriptions-item label="额外 ASR 估算费用">{{
              money(measured.cost.secondary_asr_estimate)
            }}</el-descriptions-item>
            <el-descriptions-item label="二次审核估算费用">{{
              money(measured.cost.secondary_review_estimate)
            }}</el-descriptions-item>
            <el-descriptions-item label="实际账单">待对账</el-descriptions-item>
          </el-descriptions>
          <p>
            按创建时单价估算：{{
              measured.prices
            }}。观测回复轮数不等于供应商确认计费轮数；审核按请求尝试估算。未知价格显示未估算。
          </p>
          <el-table :data="measured.turns">
            <el-table-column prop="epoch" label="响应序号" />
            <el-table-column prop="status" label="响应状态" />
            <el-table-column label="套件首包 ms"
              ><template #default="{ row }">{{
                number(row.provider_first_audio_ms)
              }}</template></el-table-column
            >
            <el-table-column label="平台放行等待 ms"
              ><template #default="{ row }">{{
                number(row.platform_release_wait_ms)
              }}</template></el-table-column
            >
            <el-table-column label="浏览器回执 ms"
              ><template #default="{ row }">{{
                number(row.browser_playback_ack_ms)
              }}</template></el-table-column
            >
          </el-table>
          <p>
            首包与播放回执均从服务端收到本轮输入结束计算；放行等待从首包到首次发送给浏览器计算，包含平台处理及所选审核。不同会话的差值不直接等于审核净耗时。
          </p>
        </template>
      </div>
      <el-collapse
        ><el-collapse-item title="录音与测量记录"
          ><div v-for="audio in current.audio || []" :key="audio.audio_id">
            <el-button
              link
              @click="
                download(
                  `${base}/trials/${current.id}/audio/${audio.audio_id}`,
                  audio.audio_id + '.wav',
                )
              "
              >下载
              {{
                audio.direction === 'input'
                  ? '用户输入'
                  : currentMode === 'native'
                    ? '原生输出'
                    : '二次审核后输出'
              }}
              {{ audio.audio_id }}</el-button
            >
          </div>
          <p>
            费用：待供应商账单对账；不将未知费用记为零。首包音频时间包含网络，不等于实际听到回复的时间。
          </p>
          <pre>{{ events.filter((e) => !['user_text', 'assistant_text'].includes(e.type)) }}</pre>
        </el-collapse-item></el-collapse
      >
      <el-divider />
      <h2>确认业务结果</h2>
      <p>
        工具调用先保存意向草稿。核对转写与录音后，可修正结果并写入测试客户跟进；不会自动发起下一次联系。
      </p>
      <el-form label-position="top"
        ><el-form-item label="意向"
          ><el-select v-model="intent" aria-label="意向"
            ><el-option label="有意向" value="interested" /><el-option
              label="明确拒绝"
              value="declined" /><el-option label="待确认" value="uncertain" /></el-select
        ></el-form-item>
        <el-form-item label="跟进记录"
          ><el-input v-model="note" type="textarea" aria-label="跟进记录" maxlength="1000"
        /></el-form-item>
        <el-form-item label="下次跟进时间（UTC ISO，可留空）"
          ><el-input
            v-model="next"
            aria-label="下次跟进时间"
            placeholder="例如 2026-11-01T06:00:00Z"
        /></el-form-item>
        <el-checkbox v-model="confirmed"
          >我已核对转写、意向和时间，确认写入所选测试客户</el-checkbox
        >
        <div>
          <el-button
            type="primary"
            :loading="busy"
            :disabled="active || !confirmed || !note.trim() || current.published_json !== '{}'"
            @click="publish"
            >确认写入线索跟进</el-button
          ><el-button
            v-if="current.published_json !== '{}'"
            @click="$router.push('/admin/operations/leads?contact_id=' + current.contact_id)"
            >查看客户跟进</el-button
          >
        </div>
      </el-form>
    </el-card>
    <el-card
      ><template #header>试聊历史</template
      ><el-radio-group v-model="historyMode" aria-label="历史方案筛选">
        <el-radio value="all">全部记录</el-radio><el-radio value="native">仅百炼原生</el-radio
        ><el-radio value="secondary_review">仅二次审核对照</el-radio>
      </el-radio-group>
      <el-table :data="filteredHistory"
        ><el-table-column prop="id" label="试聊编号" /><el-table-column label="方案"
          ><template #default="{ row }">{{ modeLabel(modeOf(row)) }}</template></el-table-column
        >
        <el-table-column label="套件估算"
          ><template #default="{ row }">{{
            money(metricsOf(row).cost?.suite_estimate)
          }}</template></el-table-column
        >
        <el-table-column label="额外 ASR 估算"
          ><template #default="{ row }">{{
            money(metricsOf(row).cost?.secondary_asr_estimate)
          }}</template></el-table-column
        >
        <el-table-column label="二次审核估算"
          ><template #default="{ row }">{{
            money(metricsOf(row).cost?.secondary_review_estimate)
          }}</template></el-table-column
        ><el-table-column prop="contact_id" label="客户 ID" /><el-table-column
          prop="status"
          label="状态"
        /><el-table-column label="创建时间"
          ><template #default="{ row }">{{ formatDate(row.created_at) }}</template></el-table-column
        ><el-table-column label="操作"
          ><template #default="{ row }"
            ><el-button link :disabled="active" @click="load(row.id)">查看</el-button></template
          ></el-table-column
        ></el-table
      ></el-card
    >
  </section>
</template>
<style scoped>
.bailian-trial {
  display: grid;
  gap: 16px;
  max-width: 1100px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 300px;
  overflow: auto;
}
.dialogue {
  max-height: 360px;
  overflow: auto;
  line-height: 1.8;
}
.el-select {
  min-width: 260px;
}
.el-form-item {
  max-width: 760px;
}
</style>
