<script setup>
import { ref, computed, onUnmounted } from 'vue';
import { request, session } from '../session';
import { formatDate } from '../api';
import { useData } from '../data';
import { ElMessage } from 'element-plus';
const profiles = useData('/api/v1/admin/voice-ai/profiles');
const ai = useData('/api/v1/admin/settings/ai');
const configuration = useData('/api/v1/admin/voice-ai/configuration');
const configProfile = ref('aliyun-cascade'),
  configChanges = ref({}),
  configSaving = ref(false);
const profileFields = {
  'aliyun-cascade': [
    'DASHSCOPE_API_KEY',
    'VOICE_AI_ALI_ASR_MODEL',
    'VOICE_AI_ALI_LLM_MODEL',
    'VOICE_AI_ALI_TTS_MODEL',
    'VOICE_AI_ALI_VOICE',
  ],
  'aliyun-realtime': [
    'DASHSCOPE_API_KEY',
    'VOICE_AI_ALI_REALTIME_URL',
    'VOICE_AI_ALI_REALTIME_MODEL',
    'VOICE_AI_ALI_REALTIME_VOICE',
  ],
  'volcengine-cascade': [
    'VOICE_AI_VOLC_ASR_API_KEY',
    'VOICE_AI_VOLC_ASR_APP_ID',
    'VOICE_AI_VOLC_ASR_ACCESS_KEY',
    'VOICE_AI_VOLC_TTS_API_KEY',
    'VOICE_AI_VOLC_TTS_APP_ID',
    'VOICE_AI_VOLC_TTS_ACCESS_KEY',
    'VOICE_AI_VOLC_LLM_API_KEY',
    'VOICE_AI_VOLC_LLM_MODEL',
    'VOICE_AI_VOLC_ASR_RESOURCE',
    'VOICE_AI_VOLC_TTS_RESOURCE',
    'VOICE_AI_VOLC_VOICE',
  ],
  'volcengine-realtime': [
    'VOICE_AI_VOLC_REALTIME_API_KEY',
    'VOICE_AI_VOLC_REALTIME_APP_ID',
    'VOICE_AI_VOLC_REALTIME_ACCESS_KEY',
    'VOICE_AI_VOLC_REALTIME_MODEL',
    'VOICE_AI_VOLC_REALTIME_VOICE',
  ],
  'tencent-cascade': [
    'VOICE_AI_TENCENT_APP_ID',
    'VOICE_AI_TENCENT_SECRET_ID',
    'VOICE_AI_TENCENT_SECRET_KEY',
    'VOICE_AI_TENCENT_LLM_API_KEY',
    'VOICE_AI_TENCENT_ASR_MODEL',
    'VOICE_AI_TENCENT_LLM_MODEL',
    'VOICE_AI_TENCENT_VOICE',
  ],
  'baidu-realtime': [
    'VOICE_AI_BAIDU_API_KEY',
    'VOICE_AI_BAIDU_REALTIME_MODEL',
    'VOICE_AI_BAIDU_REALTIME_VOICE',
  ],
};
const credentialFields = computed(() =>
  (profileFields[configProfile.value] || [])
    .map((key) => configuration.data.value?.fields?.find((f) => f.key === key))
    .filter(Boolean),
);
function configLabel(key) {
  if (key === 'DASHSCOPE_API_KEY') return '百炼 API Key';
  const part = key.replace(/^VOICE_AI_(ALI|VOLC|TENCENT|BAIDU)_/, '');
  return part.replaceAll('_', ' ');
}
function configInput(field) {
  return Object.hasOwn(configChanges.value, field.key)
    ? configChanges.value[field.key]
    : field.secret
      ? ''
      : field.value;
}
async function saveConfiguration() {
  configSaving.value = true;
  error.value = '';
  try {
    await request('/api/v1/admin/voice-ai/configuration', 'PUT', { changes: configChanges.value });
    if (!valid()) return;
    configChanges.value = {};
    await Promise.all([configuration.load(), profiles.load()]);
    ElMessage.success('供应商配置已加密保存，仅后续新测试和新通话使用。');
  } catch (e) {
    if (valid()) error.value = e.message;
  } finally {
    if (valid()) configSaving.value = false;
  }
}

const scripts = useData('/api/v1/script-templates?page=1&size=100');
const sets = useData('/api/v1/admin/voice-ai/test-sets');
const selected = ref('legacy'),
  route = ref('cascade'),
  approved = ref(false);
const playbackMode = ref('reviewed');
const audioSources = ref({}),
  audioLoading = ref('');
const saving = ref(false),
  error = ref(''),
  activeSet = ref(null),
  runs = ref([]);
const setName = ref(''),
  scriptId = ref(null),
  scriptText = ref(''),
  sampleText = ref(''),
  audioFiles = ref([]);
const busy = ref(false),
  runningProfile = ref(''),
  selectedProfiles = ref([]),
  resultsBusy = ref(false);
const epoch = session.epoch;
const controller = new AbortController();
let setSerial = 0;
onUnmounted(() => {
  controller.abort();
  Object.values(audioSources.value).forEach((url) => URL.revokeObjectURL(url));
});
const valid = () => !controller.signal.aborted && session.epoch === epoch;
const routeProfiles = computed(() =>
  (profiles.data.value || []).filter((p) => p.route === route.value),
);
const activeName = computed(
  () =>
    (profiles.data.value || []).find((p) => p.id === ai.data.value?.data?.voice_backend)?.name ||
    '原有链路',
);
const allResults = computed(() =>
  runs.value.flatMap((run) =>
    run.results.map((result, index) => ({
      ...result,
      run_id: run.id,
      profile_name: run.profile.name,
      route: run.profile.route,
      sample: index + 1,
      created_at: run.created_at,
    })),
  ),
);
async function saveActive() {
  error.value = '';
  saving.value = true;
  try {
    const data = {
      ...ai.data.value.data,
      voice_backend: selected.value,
      external_llm_enabled: approved.value,
      realtime_playback_mode: playbackMode.value,
    };
    await request('/api/v1/admin/settings/ai', 'PUT', { data });
    if (!valid()) return;
    await ai.load();
    ElMessage.success('已保存。仅后续通话使用新方案，进行中的通话保持原配置。');
  } catch (e) {
    if (valid()) error.value = e.message;
  } finally {
    if (valid()) saving.value = false;
  }
}
function chooseScript() {
  const script = (scripts.data.value || []).find((s) => s.id === scriptId.value);
  scriptText.value = script?.content || '';
}
async function filesChanged(event) {
  error.value = '';
  try {
    const files = Array.from(event.target.files || []);
    if (files.length > 10 || files.reduce((n, f) => n + f.size, 0) > 2200000)
      throw new Error('最多选择 10 个 WAV，总大小不超过 2.2MB。');
    const loaded = [];
    for (const file of files) {
      const base64 = await new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result).split(',')[1]);
        reader.onerror = reject;
        reader.readAsDataURL(file);
      });
      loaded.push({ name: file.name, audio_wav_base64: base64 });
    }
    if (valid()) audioFiles.value = loaded;
  } catch (e) {
    error.value = e.message;
    event.target.value = '';
  }
}
async function createSet() {
  busy.value = true;
  error.value = '';
  try {
    const samples = audioFiles.value.length
      ? audioFiles.value.map((f) => ({ audio_wav_base64: f.audio_wav_base64 }))
      : sampleText.value
          .split('\n')
          .map((t) => t.trim())
          .filter(Boolean)
          .map((text) => ({ text }));
    const body = { name: setName.value, samples };
    if (scriptId.value) body.script_template_id = scriptId.value;
    else body.script = scriptText.value;
    const set = await request('/api/v1/admin/voice-ai/test-sets', 'POST', body);
    if (!valid()) return;
    await sets.load();
    await loadSet(set.id);
    ElMessage.success('已固定话术与样本，后续修改原话术不会影响这组对比。');
  } catch (e) {
    if (valid()) error.value = e.message;
  } finally {
    if (valid()) busy.value = false;
  }
}
async function loadSet(id) {
  const serial = ++setSerial;
  error.value = '';
  resultsBusy.value = true;
  try {
    const set = await request(`/api/v1/admin/voice-ai/test-sets/${id}`);
    const results = await request(`/api/v1/admin/voice-ai/test-sets/${id}/runs`);
    if (!valid() || serial !== setSerial) return;
    activeSet.value = set;
    runs.value = results;
  } catch (e) {
    if (valid()) error.value = e.message;
  } finally {
    if (valid()) resultsBusy.value = false;
  }
}
async function compare() {
  if (!activeSet.value || !selectedProfiles.value.length) return;
  busy.value = true;
  error.value = '';
  const setId = activeSet.value.id;
  try {
    for (const profile of selectedProfiles.value) {
      if (!valid()) return;
      runningProfile.value = profile;
      await request(
        `/api/v1/admin/voice-ai/test-sets/${setId}/runs`,
        'POST',
        { profile_id: profile, request_id: crypto.randomUUID() },
        { timeoutMs: 1200000, signal: controller.signal },
      );
      if (!valid()) return;
      runs.value = await request(`/api/v1/admin/voice-ai/test-sets/${setId}/runs`);
    }
  } catch (e) {
    if (valid())
      error.value = e.message + ' 若请求超时，请刷新结果核对，系统不会自动重试供应商调用。';
  } finally {
    if (valid()) {
      busy.value = false;
      runningProfile.value = '';
    }
  }
}
function statusLabel(value) {
  return (
    {
      succeeded: 'API 测试成功',
      failed: '调用失败',
      unconfigured: '未配置',
      running: '运行中',
      partial: '部分成功',
    }[value] || value
  );
}
async function loadAudio(row) {
  const key = row.run_id + ':' + row.sample_id;
  audioLoading.value = key;
  error.value = '';
  try {
    const response = await fetch(
      `/api/v1/admin/voice-ai/runs/${row.run_id}/audio/${row.sample_id}`,
      { headers: { Authorization: 'Bearer ' + session.token }, signal: controller.signal },
    );
    if (!response.ok) throw new Error('试听音频加载失败，请刷新结果后重试。');
    const blob = await response.blob();
    if (valid()) audioSources.value[key] = URL.createObjectURL(blob);
  } catch (e) {
    if (valid()) error.value = e.message;
  } finally {
    if (valid()) audioLoading.value = '';
  }
}
async function initialize() {
  await Promise.all([profiles.load(), ai.load()]);
  if (!valid()) return;
  selected.value = ai.data.value?.data?.voice_backend || 'legacy';
  approved.value = ai.data.value?.data?.external_llm_enabled || false;
  playbackMode.value = ai.data.value?.data?.realtime_playback_mode || 'reviewed';
  route.value = profiles.data.value?.find((p) => p.id === selected.value)?.route || 'cascade';
  selectedProfiles.value = (profiles.data.value || []).map((p) => p.id);
}
initialize();
</script>
<template>
  <PageHeader
    title="语音方案对比"
    description="切换供应商与技术路线，使用固定话术和相同样本进行对比。"
  >
    <el-button @click="$router.push('/admin/settings')">返回系统设置</el-button>
  </PageHeader>
  <el-alert
    v-if="error || profiles.error.value || ai.error.value || sets.error.value"
    :title="error || profiles.error.value || ai.error.value || sets.error.value"
    type="error"
    :closable="false"
    show-icon
  />
  <el-card class="voice-card" v-loading="configuration.loading.value">
    <template #header><strong>供应商账号与密钥配置</strong></template>
    <el-alert
      v-if="
        configuration.error.value ||
        (configuration.data.value && !configuration.data.value.writable)
      "
      :title="configuration.error.value || '后台加密存储尚未启用，请联系平台管理员配置加密密钥。'"
      type="error"
      :closable="false"
    />
    <p>
      填写各服务的 API
      凭据，无需云网站登录密码。密钥加密保存，只显示配置状态；留空不修改，密钥点击清空后保存可停用该字段，模型留空使用系统默认值。火山新版
      API Key 与对应完整 AppID＋Token 二选一，优先使用 API Key。
    </p>
    <el-form label-width="185px" @submit.prevent="saveConfiguration">
      <el-form-item label="配置方案"
        ><el-select v-model="configProfile" aria-label="凭据配置方案"
          ><el-option
            v-for="p in profiles.data.value || []"
            :key="p.id"
            :value="p.id"
            :label="p.name" /></el-select
      ></el-form-item>
      <el-form-item
        v-for="field in credentialFields"
        :key="field.key"
        :label="configLabel(field.key)"
      >
        <el-input
          :model-value="configInput(field)"
          :aria-label="field.key"
          :type="field.secret ? 'password' : 'text'"
          :show-password="field.secret"
          autocomplete="off"
          :maxlength="4096"
          :placeholder="
            field.configured && field.secret ? '已配置（隐藏），留空保持不变' : '请输入'
          "
          @update:model-value="
            (value) => {
              if (field.secret && value === '') delete configChanges[field.key];
              else configChanges[field.key] = value;
            }
          "
        />
        <small
          >{{ field.key }} · {{ field.configured ? '已配置' : '未配置' }} · {{ field.source
          }}{{
            Object.hasOwn(configChanges, field.key) && configChanges[field.key] === ''
              ? ' · 待清空'
              : ''
          }}</small
        >
        <el-button
          v-if="field.configured"
          text
          :aria-label="'清空 ' + field.key"
          @click="configChanges[field.key] = ''"
          >清空</el-button
        >
      </el-form-item>
      <el-form-item
        ><el-button
          type="primary"
          native-type="submit"
          :disabled="!configuration.data.value?.writable || !Object.keys(configChanges).length"
          :loading="configSaving"
          >加密保存供应商配置</el-button
        ></el-form-item
      >
    </el-form>
  </el-card>
  <el-card class="voice-card" v-loading="ai.loading.value || profiles.loading.value">
    <template #header><strong>后续通话方案</strong></template>
    <p>
      当前方案：<strong data-testid="active-voice-profile">{{ activeName }}</strong
      >。每通电话固定话术和配置，切换不影响进行中的通话。
    </p>
    <el-form label-width="140px" @submit.prevent="saveActive">
      <el-form-item label="技术路线"
        ><el-select
          v-model="route"
          aria-label="技术路线"
          @change="selected = routeProfiles[0]?.id || 'legacy'"
          ><el-option label="三段式 ASR → 模型 → TTS" value="cascade" /><el-option
            label="端到端实时语音"
            value="realtime" /></el-select
      ></el-form-item>
      <el-form-item label="供应商组合"
        ><el-select v-model="selected" aria-label="供应商组合"
          ><el-option label="原有链路" value="legacy" /><el-option
            v-for="p in routeProfiles"
            :key="p.id"
            :label="p.name"
            :value="p.id" /></el-select
      ></el-form-item>
      <el-form-item label="端到端播放方式">
        <el-select v-model="playbackMode" aria-label="端到端播放方式">
          <el-option label="审核整段后播放" value="reviewed" />
          <el-option label="实时流式播放" value="streaming" />
        </el-select>
        <p>实时流式模式边生成边播放；整段审核不通过时会中止，但此前可能已播出部分回复。</p>
      </el-form-item>
      <el-form-item label="外部模型调用"
        ><el-checkbox v-model="approved" aria-label="允许外部模型调用"
          >允许将测试样本及通话所需内容发送至所选供应商</el-checkbox
        ></el-form-item
      >
      <el-form-item
        ><el-button type="primary" native-type="submit" :loading="saving" :disabled="!ai.data.value"
          >保存通话方案</el-button
        ></el-form-item
      >
    </el-form>
    <el-alert
      title="电话联调需要平台启用实时媒体并配置供应商凭据；配置齐全不等于真实线路已验证。端到端默认审核整段后播放，可选择实时流式播放进行低延迟对标；对比测试记录原始 API 首包时间。"
      type="info"
      :closable="false"
    />
  </el-card>
  <el-card class="voice-card">
    <template #header><strong>六套接入组合与配置状态</strong></template>
    <el-table :data="profiles.data.value || []" row-key="id">
      <el-table-column prop="name" label="组合" min-width="180" />
      <el-table-column label="路线" width="130"
        ><template #default="{ row }">{{
          row.route === 'cascade' ? '三段式' : '端到端实时'
        }}</template></el-table-column
      >
      <el-table-column label="配置" min-width="200"
        ><template #default="{ row }"
          ><el-tag :type="row.configured ? 'warning' : 'info'">{{
            row.configured ? '配置齐全，待联调' : '缺少配置'
          }}</el-tag>
          <div v-if="row.volc_auth" class="voice-missing" data-testid="voice-auth-modes">
            {{
              Object.entries(row.volc_auth)
                .map(
                  ([service, auth]) =>
                    service.toUpperCase() +
                    '：' +
                    (auth.mode === 'api_key' ? 'API Key' : 'AppID＋Token'),
                )
                .join('；')
            }}
          </div>
          <div class="voice-missing">{{ row.missing.join('、') }}</div>
          <div>{{ row.notice }}</div></template
        ></el-table-column
      >
      <el-table-column label="模型与音色" min-width="220"
        ><template #default="{ row }">{{
          [
            row.asr_model || row.asr_resource,
            row.llm_model,
            row.tts_model,
            row.voice,
            row.realtime_model,
            row.realtime_voice,
          ]
            .filter(Boolean)
            .join(' / ')
        }}</template></el-table-column
      >
      <el-table-column prop="verification" label="验证状态" min-width="180" />
    </el-table>
  </el-card>
  <el-card class="voice-card">
    <template #header><strong>建立统一对比样本</strong></template>
    <el-form label-width="140px" @submit.prevent="createSet">
      <el-form-item label="样本组名称"
        ><el-input
          v-model="setName"
          aria-label="样本组名称"
          maxlength="120"
          placeholder="例如：服务介绍与拒绝处理第一组"
      /></el-form-item>
      <el-form-item label="已有话术"
        ><el-select v-model="scriptId" clearable aria-label="已有话术" @change="chooseScript"
          ><el-option
            v-for="s in (scripts.data.value || []).filter((s) => s.is_active)"
            :key="s.id"
            :value="s.id"
            :label="s.name" /></el-select
      ></el-form-item>
      <el-form-item label="统一话术"
        ><el-input
          v-model="scriptText"
          type="textarea"
          :rows="5"
          :readonly="!!scriptId"
          aria-label="统一话术"
          maxlength="12000"
      /></el-form-item>
      <el-form-item label="WAV 音频样本" for="voice-ai-wavs"
        ><input
          id="voice-ai-wavs"
          aria-label="WAV 音频样本"
          type="file"
          accept=".wav,audio/wav"
          multiple
          :disabled="busy"
          @change="filesChanged"
        /><span>{{ audioFiles.map((f) => f.name).join('、') }}</span></el-form-item
      >
      <el-form-item label="文字样本"
        ><el-input
          v-model="sampleText"
          type="textarea"
          :rows="3"
          aria-label="文字样本"
          :disabled="audioFiles.length > 0"
          placeholder="没有音频时可按行输入，仅用于三段式的模型与合成测试。六套完整对比请使用 WAV。"
      /></el-form-item>
      <el-form-item
        ><el-button
          type="primary"
          native-type="submit"
          :loading="busy"
          :disabled="
            !setName.trim() || !scriptText.trim() || (!sampleText.trim() && !audioFiles.length)
          "
          >固定话术并保存样本</el-button
        ></el-form-item
      >
    </el-form>
    <p>
      每条音频为 0.2—30 秒、单声道 16bit PCM WAV，支持
      8/16/24/48kHz。保存后固定话术全文与音频，所有组合复用同一份样本；最多 10 条。
    </p>
  </el-card>
  <el-card class="voice-card" v-loading="resultsBusy">
    <template #header><strong>运行对比与查看历史</strong></template>
    <el-select
      :model-value="activeSet?.id"
      :disabled="busy"
      aria-label="已保存样本组"
      @change="loadSet"
      ><el-option v-for="s in sets.data.value || []" :key="s.id" :label="s.name" :value="s.id"
    /></el-select>
    <template v-if="activeSet">
      <p data-testid="voice-script-sha">话术校验值：{{ activeSet.script_sha }}</p>
      <el-collapse
        ><el-collapse-item title="查看固定话术与样本">
          <pre class="voice-script">{{ activeSet.script_snapshot }}</pre>
          <p v-for="(s, i) in activeSet.samples" :key="s.id">
            样本 {{ i + 1 }}：{{ s.kind === 'audio' ? 'WAV 音频' : s.text }}
          </p></el-collapse-item
        ></el-collapse
      >
      <el-checkbox-group v-model="selectedProfiles" aria-label="参与对比的方案"
        ><el-checkbox
          v-for="p in profiles.data.value || []"
          :key="p.id"
          :value="p.id"
          :disabled="busy"
          >{{ p.name }}</el-checkbox
        ></el-checkbox-group
      >
      <div class="actions">
        <el-button
          type="primary"
          :loading="busy"
          :disabled="!selectedProfiles.length"
          @click="compare"
          >运行所选组合</el-button
        ><el-button :disabled="busy" @click="loadSet(activeSet.id)">刷新结果</el-button
        ><span v-if="runningProfile">正在测试：{{ runningProfile }}</span>
      </div>
      <p>
        运行会调用供应商并产生其 API
        费用。缺少配置的组合会单独记录，其他组合继续；超时与失败不会自动重试。结果为 API
        测试，不代表电话线路或生产验收通过。
      </p>
      <el-alert
        v-if="runs.some((r) => r.status === 'running')"
        title="存在运行中或结果尚未确认的请求。请先刷新核对，避免重复产生调用费用。"
        type="warning"
        :closable="false"
      />
      <el-table :data="allResults" data-testid="voice-comparison-results">
        <el-table-column label="测试时间" min-width="160"
          ><template #default="{ row }">{{ formatDate(row.created_at) }}</template></el-table-column
        >
        <el-table-column prop="profile_name" label="方案" min-width="160" /><el-table-column
          prop="sample"
          label="样本"
          width="70"
        />
        <el-table-column label="状态" min-width="140"
          ><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column
        >
        <el-table-column label="响应" min-width="200"
          ><template #default="{ row }"
            ><p>{{ row.transcript }}</p>
            <p>{{ row.reply }}</p>
            <p>{{ row.error }}</p>
            <small>{{ row.scope }}</small></template
          ></el-table-column
        >
        <el-table-column label="输入音频结束到首包" width="160"
          ><template #default="{ row }"
            >{{ row.post_input_first_audio_ms ?? '—' }} ms</template
          ></el-table-column
        >
        <el-table-column label="全流程耗时" width="120"
          ><template #default="{ row }">{{ row.total_ms ?? '—' }} ms</template></el-table-column
        >
        <el-table-column label="回复试听" min-width="240"
          ><template #default="{ row }"
            ><el-button
              v-if="row.audio_available && !audioSources[row.run_id + ':' + row.sample_id]"
              :loading="audioLoading === row.run_id + ':' + row.sample_id"
              :disabled="!!audioLoading"
              @click="loadAudio(row)"
              >加载试听</el-button
            >
            <audio
              v-if="audioSources[row.run_id + ':' + row.sample_id]"
              :src="audioSources[row.run_id + ':' + row.sample_id]"
              controls
              preload="none"
              aria-label="回复试听" /></template
        ></el-table-column>
      </el-table>
    </template>
    <el-empty v-else description="先保存一组话术与样本，或选择已有样本组" />
  </el-card>
</template>
<style scoped>
.voice-card {
  margin-bottom: 20px;
}
.voice-card :deep(.el-select) {
  min-width: 260px;
}
.voice-missing {
  overflow-wrap: anywhere;
  font-size: 12px;
  margin-top: 6px;
}
.voice-script {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
audio {
  max-width: 230px;
}
.actions {
  margin-top: 14px;
}
</style>
