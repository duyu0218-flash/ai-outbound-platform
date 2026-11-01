<script setup>
import { ref, computed, watch } from 'vue';
import { request } from '../session';
import { useData, action } from '../data';
import { formatDate } from '../api';
import { ElMessage } from 'element-plus';

const LABELS = {
  CALL_ANSWERED: '已接通',
  CALL_NO_ANSWER: '无人接听',
  CALL_BUSY: '忙线',
  CALL_REJECTED: '拒接',
  CALL_INVALID: '空号/无效号码',
  CALL_POWER_OFF: '关机/不在服务区',
  CALL_SUSPENDED: '停机/欠费',
  CALL_LINE_FAIL: '线路故障（不计入用户）',
  ANS_CONVERSATION: '有效对话',
  ANS_INSTANT_HANGUP: '秒挂',
  ANS_SHORT_HANGUP: '短通话挂断',
  ANS_VOICE_ASSISTANT: '语音助手代接',
  ANS_VOICEMAIL: '语音信箱',
  ANS_NOT_OWNER: '非机主',
  ANS_REFUSE_HARD: '明确拒绝',
  INTENT_A: 'A 高意向',
  INTENT_B: 'B 有兴趣',
  INTENT_C: 'C 暂无需要',
  INTENT_D: 'D 拒绝/勿扰',
  INTENT_E: 'E 无法判断',
  SKIPPED: '未判定',
};
const REVIEW = { NONE: '无需复核', PENDING: '待复核', CONFIRMED: '已确认', CORRECTED: '已纠正' };
const LAYER = { connection: '接通', answer: '接听质量', intent: '意向' };
const label = (v) => (v ? LABELS[v] || v : '-');
const pct = (v) => (v === null || v === undefined ? '-' : (v * 100).toFixed(1) + '%');

const tab = ref('browse'),
  range = ref([]),
  campaign = ref(''),
  code = ref(''),
  reviewState = ref(''),
  page = ref(1);
const meta = useData('/api/v1/marketing/outcomes/meta');

function utc(day, next) {
  const [y, m, d] = day.split('-').map(Number);
  return new Date(y, m - 1, d + (next ? 1 : 0)).toISOString().slice(0, 19);
}
const scope = computed(() => {
  const q = {};
  if (range.value?.length === 2) {
    q.from = utc(range.value[0], false);
    q.to = utc(range.value[1], true);
  }
  if (campaign.value) q.campaign_id = campaign.value;
  return q;
});
const listUrl = computed(
  () =>
    '/api/v1/marketing/outcomes?' +
    new URLSearchParams({
      ...scope.value,
      code: code.value,
      review_state: reviewState.value,
      page: String(page.value),
      size: '50',
    }),
);
const list = useData(() => (tab.value === 'browse' ? listUrl.value : ''));
const stats = useData(() =>
  tab.value === 'stats'
    ? '/api/v1/marketing/outcomes/stats?' + new URLSearchParams(scope.value)
    : '',
);
const queue = useData(() =>
  tab.value === 'review'
    ? '/api/v1/marketing/outcomes/review-queue?' +
      new URLSearchParams({ ...scope.value, size: '100' })
    : '',
);
const accuracy = useData(() =>
  tab.value === 'accuracy'
    ? '/api/v1/marketing/outcomes/accuracy?' +
      new URLSearchParams({ from: scope.value.from || '', to: scope.value.to || '' })
    : '',
);
const rules = useData(() => (tab.value === 'rules' ? '/api/v1/marketing/outcomes/rules' : ''));
watch([range, campaign, code, reviewState], () => (page.value = 1));

const allCodes = computed(() => [
  ...(meta.data.value?.connection_codes || []),
  ...(meta.data.value?.answer_codes || []),
  ...(meta.data.value?.intent_codes || []),
  'SKIPPED',
]);
const columns = [
  { key: 'call_finished_at', label: '结束时间', date: true },
  { key: 'phone_mask', label: '号码' },
  { key: 'campaign_name', label: '外呼任务', render: (v) => v || '-' },
  { key: 'connection_code', label: '接通', render: label },
  { key: 'answer_code', label: '接听质量', render: label },
  { key: 'intent_code', label: '意向', render: label },
  { key: 'confidence', label: '置信度', render: (v) => (v === null ? '-' : Number(v).toFixed(2)) },
  {
    key: 'review_state',
    label: '复核',
    render: (v, row) =>
      row.state === 'SKIPPED' ? '未判定：' + (row.skip_reason || '') : REVIEW[v] || v,
  },
  { key: 'rule_version', label: '规则版本', render: (v) => (v ? 'v' + v : '内置') },
];

// ---------------------------------------------------------------- detail & review
const detailId = ref(null),
  detail = ref(null),
  form = ref({}),
  busy = ref(false);
async function open(row) {
  detailId.value = row.call_id;
  detail.value = await request('/api/v1/marketing/outcomes/calls/' + row.call_id);
  form.value = {
    connection_code: detail.value.connection_code,
    answer_code: detail.value.answer_code,
    intent_code: detail.value.intent_code,
    note: '',
  };
}
function refreshAll() {
  list.load();
  queue.load();
}
async function submitReview() {
  busy.value = true;
  try {
    await action(
      () =>
        request('/api/v1/marketing/outcomes/calls/' + detailId.value + '/review', 'POST', {
          connection_code: form.value.connection_code,
          answer_code:
            form.value.connection_code === 'CALL_ANSWERED' ? form.value.answer_code : null,
          intent_code:
            form.value.answer_code === 'ANS_CONVERSATION' ? form.value.intent_code : null,
          note: form.value.note,
        }),
      { success: '复核已保存，纠正结果已写入触达事件' },
    );
    detailId.value = null;
    refreshAll();
  } finally {
    busy.value = false;
  }
}
async function learnPhrase() {
  const r = await action(
    () =>
      request(
        '/api/v1/marketing/outcomes/calls/' + detailId.value + '/assistant-phrase',
        'POST',
        {},
      ),
    { success: '' },
  );
  ElMessage.success(`已加入规则草稿 v${r.version} 的语音助手话术库，发布后生效`);
}
async function reclassify() {
  await action(
    () => request('/api/v1/marketing/outcomes/calls/' + detailId.value + '/classify', 'POST'),
    {
      success: '已按当前规则重新判定',
    },
  );
  await open({ call_id: detailId.value });
  refreshAll();
}
const layers = computed(() => detail.value?.evidence?.layers || {});
const inputs = computed(() => detail.value?.evidence?.inputs || {});

// ---------------------------------------------------------------- rules
const NUMBERS = [
  ['ring_timeout_sec', '振铃超时（秒）', '超过即判无人接听'],
  ['quick_reject_sec', '快速拒接（秒）', '振铃这么短就结束判为拒接'],
  ['instant_hangup_sec', '秒挂阈值（秒）', '接通后多少秒内对方挂断算秒挂'],
  ['short_hangup_sec', '短通话阈值（秒）', '有效发言不超过 1 轮时'],
  ['min_valid_chars', '有效发言最少字数', '去掉语气词后的字数'],
  ['min_asr_confidence', '识别置信度下限', '低于此值的转写不算有效发言'],
  ['review_confidence', '人工复核阈值', '接通/接听质量置信度低于此值进入复核'],
  ['assistant_llm_confidence', '语音助手大模型阈值', '大模型判定置信度不低于此值才采纳'],
  ['intent_llm_confidence', '意向大模型阈值', '低于此值进入人工复核'],
];
const LISTS = [
  ['assistant_phrases', '语音助手话术'],
  ['voicemail_phrases', '语音信箱话术'],
  ['not_owner_phrases', '非机主话术'],
  ['refuse_hard_phrases', '明确拒绝话术'],
  ['filler_words', '语气词（不计入有效发言）'],
  ['carrier_phrases.power_off', '运营商提示音：关机/不在服务区'],
  ['carrier_phrases.suspended', '运营商提示音：停机/欠费'],
  ['carrier_phrases.invalid', '运营商提示音：空号'],
  ['carrier_phrases.busy', '运营商提示音：通话中'],
  ['intent.A', '意向 A 关键词'],
  ['intent.B', '意向 B 关键词'],
  ['intent.C', '意向 C 关键词'],
  ['intent.D', '意向 D 关键词'],
  ['intent.nodes.A', '意向 A 话术节点'],
  ['intent.nodes.B', '意向 B 话术节点'],
];
const editor = ref(null);
const get = (obj, path) => path.split('.').reduce((o, k) => o?.[k], obj);
function set(obj, path, value) {
  const keys = path.split('.');
  let o = obj;
  keys.slice(0, -1).forEach((k) => (o = o[k] = o[k] || {}));
  o[keys.at(-1)] = value;
}
watch(
  () => rules.data.value,
  (r) => {
    const src = r?.draft?.config;
    if (!src) return (editor.value = null);
    const config = JSON.parse(JSON.stringify(src));
    const text = {};
    LISTS.forEach(([k]) => (text[k] = (get(config, k) || []).join('\n')));
    editor.value = { version: r.draft.version, note: r.draft.note || '', config, text };
  },
);
async function newDraft() {
  await action(() => request('/api/v1/marketing/outcomes/rules', 'POST', {}), {
    refresh: rules.load,
    success: '已基于当前版本创建草稿',
  });
}
function draftConfig() {
  const config = JSON.parse(JSON.stringify(editor.value.config));
  LISTS.forEach(([k]) =>
    set(
      config,
      k,
      editor.value.text[k]
        .split('\n')
        .map((s) => s.trim())
        .filter(Boolean),
    ),
  );
  return config;
}
async function saveDraft() {
  await action(
    () =>
      request('/api/v1/marketing/outcomes/rules/' + editor.value.version, 'PUT', {
        config: draftConfig(),
        note: editor.value.note,
      }),
    { refresh: rules.load, success: '草稿已保存' },
  );
}
const publishing = ref(false),
  rerunRange = ref([]);
async function publish() {
  await saveDraft();
  const body = {};
  if (rerunRange.value?.length === 2) {
    body.rerun_from = utc(rerunRange.value[0], false);
    body.rerun_to = utc(rerunRange.value[1], true);
  }
  const r = await action(
    () =>
      request(
        '/api/v1/marketing/outcomes/rules/' + editor.value.version + '/publish',
        'POST',
        body,
      ),
    { refresh: rules.load, success: '' },
  );
  publishing.value = false;
  ElMessage.success(
    r.rerun
      ? `已发布 v${r.version}，重跑 ${r.rerun.processed} 通，结果变化 ${r.rerun.changed} 通${r.rerun.truncated ? '（超出上限，请缩小时间范围再跑）' : ''}`
      : `已发布 v${r.version}，新通话按新规则判定`,
  );
  meta.load();
}
async function rerun() {
  if (range.value?.length !== 2) return ElMessage.warning('请先在上方选择日期范围');
  const r = await action(
    () =>
      request('/api/v1/marketing/outcomes/rerun', 'POST', {
        from: scope.value.from,
        to: scope.value.to,
        campaign_id: campaign.value || null,
      }),
    { confirm: '按当前生效规则重新判定所选范围内的通话？已人工复核的结果保持不变。', success: '' },
  );
  ElMessage.success(`重跑 ${r.processed} 通，结果变化 ${r.changed} 通`);
  refreshAll();
}
</script>
<template>
  <PageHeader
    title="触达结果判定"
    description="外呼结束后自动判定接通、接听质量（秒挂、语音助手等）和意向等级，低置信度结果进入人工复核。"
    ><el-button @click="rerun">按范围重跑</el-button></PageHeader
  >
  <el-card>
    <div class="actions">
      <el-date-picker
        v-model="range"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        style="width: 260px"
      />
      <el-select
        v-model="campaign"
        clearable
        filterable
        placeholder="全部外呼任务"
        style="width: 200px"
        ><el-option
          v-for="c in meta.data.value?.campaigns || []"
          :key="c.id"
          :label="c.name"
          :value="c.id"
      /></el-select>
      <span class="hint"
        >当前规则：{{
          meta.data.value?.active_rule_version
            ? 'v' + meta.data.value.active_rule_version
            : '内置默认'
        }}
        · 自动判定{{
          meta.data.value?.worker_enabled ? '已开启' : '未开启（MARKETING_WORKERS_ENABLED）'
        }}
        · 大模型{{ meta.data.value?.llm_available ? '已接入' : '未配置，仅用规则' }}</span
      >
    </div>
    <el-tabs v-model="tab">
      <el-tab-pane label="判定结果" name="browse">
        <div class="actions">
          <el-select
            v-model="code"
            clearable
            filterable
            placeholder="全部结果码"
            style="width: 200px"
            ><el-option v-for="c in allCodes" :key="c" :label="label(c)" :value="c"
          /></el-select>
          <el-select v-model="reviewState" clearable placeholder="全部复核状态" style="width: 160px"
            ><el-option v-for="(v, k) in REVIEW" :key="k" :label="v" :value="k"
          /></el-select>
        </div>
        <el-alert v-if="list.error.value" :title="list.error.value" type="error" />
        <DataGrid
          :rows="list.data.value?.items || []"
          :loading="list.loading.value"
          :columns="columns"
          row-key="call_id"
          ><template #actions="{ row }"
            ><el-button
              size="small"
              type="primary"
              :disabled="row.state === 'SKIPPED'"
              @click="open(row)"
              >详情 / 纠正</el-button
            ></template
          ></DataGrid
        >
        <el-pagination
          layout="total, prev, pager, next"
          :total="list.data.value?.total || 0"
          :page-size="50"
          v-model:current-page="page"
        />
      </el-tab-pane>
      <el-tab-pane label="结果分布" name="stats">
        <el-alert v-if="stats.error.value" :title="stats.error.value" type="error" />
        <MetricCards
          :data="stats.data.value"
          :items="[
            { key: 'total', label: '已判定通话' },
            { key: 'connection_rate', label: '接通率（不含线路故障）', render: pct },
            { key: 'instant_hangup_rate', label: '秒挂率（占接通）', render: pct },
            { key: 'voice_assistant_share', label: '语音助手占比（占接通）', render: pct },
            { key: 'conversation_rate', label: '有效对话率（占接通）', render: pct },
            { key: 'intent_ab_share', label: 'A+B 意向占比', render: pct },
            { key: 'pending_review', label: '待人工复核' },
            { key: 'skipped', label: '未判定' },
          ]"
        />
        <el-row :gutter="16">
          <el-col v-for="(name, layer) in LAYER" :key="layer" :md="8" :span="24"
            ><h3>{{ name }}</h3>
            <el-table
              :data="
                Object.entries(stats.data.value?.[layer] || {})
                  .map(([code, n]) => ({ code, n }))
                  .sort((a, b) => b.n - a.n)
              "
              size="small"
              ><el-table-column label="结果" :formatter="(r) => label(r.code)" /><el-table-column
                prop="n"
                label="通话数"
                width="90" /></el-table
          ></el-col>
        </el-row>
      </el-tab-pane>
      <el-tab-pane name="review"
        ><template #label
          >待复核<el-badge
            v-if="queue.data.value?.total"
            :value="queue.data.value.total"
            style="margin-left: 6px"
        /></template>
        <p class="hint">
          置信度低于阈值的判定会进入这里。纠正后以人工结果为准，并写入一条新的触达事件（来源带
          #review）。
        </p>
        <DataGrid
          :rows="queue.data.value?.items || []"
          :loading="queue.loading.value"
          :columns="[...columns.slice(0, 7), { key: 'review_reason', label: '复核原因' }]"
          row-key="call_id"
          ><template #actions="{ row }"
            ><el-button size="small" type="primary" @click="open(row)">复核</el-button></template
          ></DataGrid
        >
      </el-tab-pane>
      <el-tab-pane label="判定规则" name="rules">
        <el-alert v-if="rules.error.value" :title="rules.error.value" type="error" />
        <p class="hint">
          阈值和话术库按租户分版本管理。修改草稿后发布即生效；发布时可选择日期范围重跑历史通话，重跑结果幂等，已人工复核的结果不会被覆盖。
        </p>
        <el-table :data="rules.data.value?.versions || []" size="small" style="margin-bottom: 16px"
          ><el-table-column prop="version" label="版本" width="80" /><el-table-column
            label="状态"
            width="100"
            :formatter="
              (r) =>
                ({ DRAFT: '草稿', PUBLISHED: '生效中', ARCHIVED: '已归档' })[r.state] || r.state
            " /><el-table-column prop="note" label="说明" /><el-table-column
            label="发布时间"
            :formatter="(r) => formatDate(r.published_at)"
        /></el-table>
        <el-empty v-if="!editor" description="没有草稿">
          <el-button type="primary" @click="newDraft">基于当前版本新建草稿</el-button>
        </el-empty>
        <el-form v-else label-width="170px">
          <el-form-item label="草稿版本">v{{ editor.version }}</el-form-item>
          <el-form-item label="版本说明"
            ><el-input v-model="editor.note" maxlength="200"
          /></el-form-item>
          <el-row>
            <el-col v-for="[k, name, hint] in NUMBERS" :key="k" :md="12" :span="24"
              ><el-form-item :label="name"
                ><el-input-number
                  v-model="editor.config[k]"
                  :min="0"
                  :step="k.endsWith('confidence') ? 0.05 : 1"
                />
                <span class="hint" style="margin-left: 8px">{{ hint }}</span></el-form-item
              ></el-col
            >
          </el-row>
          <el-form-item label="大模型兜底"
            ><el-checkbox v-model="editor.config.assistant_llm_enabled">语音助手识别</el-checkbox
            ><el-checkbox v-model="editor.config.intent_llm_enabled"
              >意向等级</el-checkbox
            ></el-form-item
          >
          <el-row :gutter="16">
            <el-col v-for="[k, name] in LISTS" :key="k" :md="12" :span="24"
              ><el-form-item :label="name"
                ><el-input
                  v-model="editor.text[k]"
                  type="textarea"
                  :rows="4"
                  placeholder="每行一条"
              /></el-form-item>
            </el-col>
          </el-row>
          <el-form-item>
            <el-button @click="saveDraft">保存草稿</el-button>
            <el-button type="primary" @click="publishing = true">发布</el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>
      <el-tab-pane label="判定准确率" name="accuracy">
        <p class="hint">
          按人工复核样本统计：自动结果被复核员保留即为正确。日期范围按复核时间筛选。
        </p>
        <el-table :data="accuracy.data.value?.items || []" v-loading="accuracy.loading.value"
          ><el-table-column
            label="层级"
            width="120"
            :formatter="(r) => LAYER[r.layer]" /><el-table-column
            label="自动结果"
            :formatter="(r) => label(r.code)" /><el-table-column
            prop="reviewed"
            label="复核样本"
            width="110" /><el-table-column
            prop="correct"
            label="判定正确"
            width="110" /><el-table-column
            label="准确率"
            width="110"
            :formatter="(r) => pct(r.accuracy)"
        /></el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>

  <el-dialog v-model="publishing" title="发布规则" width="460px">
    <p>发布后新结束的通话按 v{{ editor?.version }} 判定。如需同时重跑历史通话，请选择日期范围：</p>
    <el-date-picker
      v-model="rerunRange"
      type="daterange"
      value-format="YYYY-MM-DD"
      start-placeholder="开始日期"
      end-placeholder="结束日期"
    />
    <template #footer
      ><el-button @click="publishing = false">取消</el-button
      ><el-button type="primary" @click="publish">确认发布</el-button></template
    >
  </el-dialog>

  <el-drawer
    :model-value="detailId !== null"
    size="60%"
    title="判定详情与人工纠正"
    destroy-on-close
    @close="detailId = null"
  >
    <template v-if="detail">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="号码">{{ detail.phone_mask }}</el-descriptions-item>
        <el-descriptions-item label="外呼任务">{{
          detail.campaign_name || '-'
        }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{
          formatDate(detail.call_finished_at)
        }}</el-descriptions-item>
        <el-descriptions-item label="规则版本">{{
          detail.rule_version ? 'v' + detail.rule_version : '内置'
        }}</el-descriptions-item>
        <el-descriptions-item label="振铃 / 通话">
          {{ inputs.ring_sec ?? '-' }} 秒 / {{ inputs.talk_sec ?? '-' }} 秒</el-descriptions-item
        >
        <el-descriptions-item label="挂断原因 / SIP">
          {{ inputs.hangup_cause || '-' }} / {{ inputs.sip_code || '-' }}</el-descriptions-item
        >
      </el-descriptions>
      <h3>自动判定依据</h3>
      <el-table :data="Object.entries(layers).filter(([, l]) => l)" size="small"
        ><el-table-column
          label="层级"
          width="100"
          :formatter="(r) => LAYER[r[0]]" /><el-table-column
          label="结果"
          width="160"
          :formatter="(r) => label(r[1].code)" /><el-table-column
          label="置信度"
          width="90"
          :formatter="(r) => Number(r[1].confidence).toFixed(2)" /><el-table-column
          label="依据"
          :formatter="(r) => r[1].reason"
      /></el-table>
      <p v-if="detail.evidence?.missing_signals?.length" class="hint">
        缺少的信号：{{ detail.evidence.missing_signals.join('、') }}
      </p>
      <h3>对话转写</h3>
      <div v-if="!detail.turns?.length" class="hint">无转写</div>
      <p v-for="(t, i) in detail.turns" :key="i">
        <el-tag size="small" :type="t.speaker_role === 'ai' ? 'info' : 'success'">{{
          t.speaker_role === 'ai' ? '机器人' : '客户'
        }}</el-tag>
        {{ t.transcript }}
      </p>
      <h3>人工纠正</h3>
      <el-form label-width="90px">
        <el-form-item label="接通"
          ><el-select v-model="form.connection_code" style="width: 240px"
            ><el-option
              v-for="c in meta.data.value?.connection_codes || []"
              :key="c"
              :label="label(c)"
              :value="c" /></el-select
        ></el-form-item>
        <el-form-item v-if="form.connection_code === 'CALL_ANSWERED'" label="接听质量"
          ><el-select v-model="form.answer_code" style="width: 240px"
            ><el-option
              v-for="c in meta.data.value?.answer_codes || []"
              :key="c"
              :label="label(c)"
              :value="c" /></el-select
        ></el-form-item>
        <el-form-item
          v-if="form.connection_code === 'CALL_ANSWERED' && form.answer_code === 'ANS_CONVERSATION'"
          label="意向"
          ><el-select v-model="form.intent_code" style="width: 240px"
            ><el-option
              v-for="c in meta.data.value?.intent_codes || []"
              :key="c"
              :label="label(c)"
              :value="c" /></el-select
        ></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" maxlength="200" /></el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="busy" @click="submitReview">保存复核</el-button>
          <el-button v-if="form.answer_code === 'ANS_VOICE_ASSISTANT'" @click="learnPhrase"
            >把客户首句加入语音助手话术库</el-button
          >
          <el-button @click="reclassify">按当前规则重新判定</el-button>
        </el-form-item>
      </el-form>
      <template v-if="detail.reviews?.length">
        <h3>复核记录</h3>
        <el-table :data="detail.reviews" size="small"
          ><el-table-column
            label="时间"
            :formatter="(r) => formatDate(r.created_at)" /><el-table-column
            label="层级"
            :formatter="(r) => LAYER[r.layer]" /><el-table-column
            label="自动结果"
            :formatter="(r) => label(r.auto_code)" /><el-table-column
            label="复核结果"
            :formatter="(r) => label(r.corrected_code)" /><el-table-column prop="note" label="备注"
        /></el-table>
      </template>
    </template>
  </el-drawer>
</template>
