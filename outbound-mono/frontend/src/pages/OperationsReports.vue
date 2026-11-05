<script setup>
const crypto = globalThis.crypto;
import { ref, reactive, onMounted, computed } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { ops, perform, parse, query, prefix } from '../operations';
import Billing from './Billing.vue';
import { download } from '../session';
import { usePageQuery } from '../page-query';
import { reportLabels } from '../benchmark';
const { isCurrent, replaceQuery } = usePageQuery();
const router = useRouter(),
  route = useRoute(),
  error = ref(''),
  busy = ref(false),
  profile = ref({}),
  rows = ref([]),
  definition = ref(''),
  tasks = ref([]),
  task = ref(null),
  views = ref([]),
  viewId = ref(''),
  name = ref('');
const section = route.path.split('/').at(-1);
const heading =
  { seating: '账号统计', dataAnalysis: '数据分析', intent: '意向统计' }[section] || '运营报表';
const q = reactive({
    dimension: section === 'seating' ? 'account' : section === 'intent' ? 'agent' : 'campaign',
    granularity: 'day',
    ...route.query,
  }),
  visible = ref([
    'answer_rate',
    'entry_rate',
    'takeover_rate',
    'not_taken',
    'successful_calls',
    'success_per_answer',
    'success_per_takeover',
    'intent_rate',
    'sessions',
    'attempts',
    'phones',
    'reached_sessions',
    'reached_phones',
    'handoff_requests',
    'handoff_received',
    'qualified_phones',
    'failed_sessions',
    'first_answered_attempts',
    'repeat_answered_attempts',
    'answered_attempts',
    'telephony_seconds',
    'ai_seconds',
    ...[
      'online_seconds',
      'online_people',
      'gender_ratio',
      'human_seconds',
      'followed',
      'average_intervention',
      'loss_count',
      'loss_rate',
      'intent_cost',
      'success_cost',
      'statement_cost',
      'reference_cost',
      'reference_intent_cost',
      'reference_success_cost',
      'active_agents',
      'per_agent_takeover',
      'per_agent_cost',
      'weekday',
    ].filter((k) => reportLabels[k]),
  ]);
const rangeKinds = { total_ranges: '通话', ai_ranges: 'AI通话', human_ranges: '人工通话' };
function rangesFor(key) {
  return q[key]
    ? String(q[key])
        .split(',')
        .map((r) => r.split('-'))
    : [];
}
function setRange(key, index, side, value) {
  const ranges = rangesFor(key);
  ranges[index][side] = value;
  q[key] = ranges.map((r) => r.join('-')).join(',');
}
function addRange(key) {
  const ranges = rangesFor(key);
  if (ranges.length < 8) q[key] = [...ranges.map((r) => r.join('-')), '0-999'].join(',');
}
function removeRange(key, index) {
  q[key] = rangesFor(key)
    .filter((_, i) => i !== index)
    .map((r) => r.join('-'))
    .join(',');
}
const labels = {
  ...reportLabels,
  answer_rate: '接通率（%）',
  entry_rate: '进线率（%）',
  takeover_rate: '接管率（%）',
  not_taken: '已结束未接管数',
  successful_calls: '合格成功数',
  successful_takeovers: '接管后成功数',
  success_per_answer: '接通成功率（%）',
  success_per_takeover: '接管成功率（%）',
  intent_rate: '意向率（%）',
  statement_cost: '账单话费（元）',
  bill_seconds: '账单计费时长（秒）',
  settled_attempts: '已对账拨打次数',
  sessions: '会话数',
  first_answered_attempts: '首呼接通数',
  repeat_answered_attempts: '复呼接通数',
  answered_attempts: '有接通回执的拨打次数',
  telephony_seconds: '有记录的通话时长（秒）',
  ai_seconds: '有记录的AI时长（秒）',

  attempts: '累计尝试',
  phones: '组内唯一号码',
  reached_sessions: '接通会话',
  reached_phones: '接通唯一号码',
  handoff_requests: '转人工会话',
  handoff_received: '已接收转接',
  qualified_phones: '合格唯一号码',
  failed_sessions: '失败会话',
};
const columns = computed(() => [
    { key: 'dimension_key', label: '维度编号' },
    { key: 'bucket', label: '时间' },
    ...visible.value.map((k) => ({ key: k, label: labels[k], sortable: true })),
  ]),
  max = computed(() => Math.max(1, ...rows.value.map((r) => r.sessions || 0)));
const durationKind = ref('total'),
  durations = ref([]),
  durationError = ref('');
const durationTotal = computed(() => durations.value.reduce((n, r) => n + Number(r.calls), 0));
const durationLabels = {
  short: '1–10秒',
  medium: '10–30秒',
  long: '30–60秒',
  extended: '60秒以上',
  unknown: '无有效时长',
};
let durationRequest = 0;
async function loadDurations() {
  const requestId = ++durationRequest;
  try {
    const result = await ops(
      '/reports/durations?' + query({ ...params(), kind: durationKind.value }),
    );
    if (requestId === durationRequest) {
      durations.value = result;
      durationError.value = '';
    }
  } catch (e) {
    if (requestId === durationRequest) {
      durations.value = [];
      durationError.value = e.message;
    }
  }
}
function durationCount(bucket) {
  return Number(durations.value.find((r) => r.duration_bucket === bucket)?.calls || 0);
}
function params() {
  const f = { ...q };
  for (const k of ['from', 'until']) if (f[k]) f[k] = new Date(f[k]).toISOString();
  return f;
}
async function load() {
  if (!isCurrent()) return;
  busy.value = true;
  error.value = '';
  try {
    const f = params();
    await replaceQuery(
      Object.fromEntries(Object.entries(f).filter(([, v]) => v !== '' && v != null)),
    );
    if (!isCurrent()) return;
    const data = await ops('/reports?' + query(f));
    if (!isCurrent()) return;
    rows.value = data.rows;
    definition.value = data.definition;
    await loadDurations();
    if (profile.value.administrator) tasks.value = await ops('/campaigns');
  } catch (e) {
    if (isCurrent()) error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function save() {
  await perform(
    () =>
      ops('/views', 'POST', {
        kind: 'reports',
        name: name.value,
        config: { filters: params(), columns: visible.value },
      }),
    async () => (views.value = await ops('/views')),
  );
}
function choose() {
  const v = views.value.find((v) => v.id === viewId.value);
  if (!v) return;
  const conf = parse(v.config_json);
  Object.keys(q).forEach((k) => delete q[k]);
  Object.assign(q, conf.filters);
  visible.value = (conf.columns || []).filter((k) => labels[k]);
  load();
}
function drill(row) {
  const f = params();
  const field = {
    account: 'actor_id',
    group: 'stat_team_id',
    line_group: 'line_group',
    city: 'city',
    script: 'script_id',
    campaign: 'campaign_id',
    agent: 'agent_id',
    line: 'line_id',
    owner: 'owner_id',
    flow_version: 'flow_version_id',
  }[q.dimension];
  if (
    field &&
    (Number(row.dimension_key) > 0 ||
      ['actor_id', 'stat_team_id', 'line_group', 'city', 'flow_version_id'].includes(field))
  )
    f[field] = row.dimension_key;
  if (row.bucket !== '全部') {
    const date = new Date(
      row.bucket.replace(' ', 'T') +
        (row.bucket.length === 7 ? '-01T00:00:00' : row.bucket.length === 10 ? 'T00:00:00' : '') +
        '+08:00',
    );
    const bucketMs = { minute: 60000, hour: 3600000, day: 86400000 }[q.granularity];
    const end =
      q.granularity === 'month'
        ? Date.UTC(Number(row.bucket.slice(0, 4)), Number(row.bucket.slice(5, 7)), 1) -
          8 * 3600000 -
          1
        : date.getTime() + bucketMs - 1;
    f.from = new Date(
      Math.max(date.getTime(), f.from ? Date.parse(f.from) : -Infinity),
    ).toISOString();
    f.until = new Date(Math.min(end, f.until ? Date.parse(f.until) : Infinity)).toISOString();
  }
  delete f.dimension;
  delete f.granularity;
  router.push({ path: prefix() + '/operations/calls', query: f });
}
onMounted(async () => {
  try {
    profile.value = await ops('/me');
    if (!isCurrent()) return;
    views.value = await ops('/views');
  } catch (e) {
    if (isCurrent()) error.value = e.message;
  }
  load();
});
</script>
<template>
  <PageHeader :title="heading" description="按任务、坐席、线路和负责人分析并下钻话单"
    ><el-button
      v-if="profile.can_export"
      @click="
        perform(() =>
          ops('/exports', 'POST', {
            kind: 'reports',
            query: params(),
            request_key: crypto.randomUUID(),
          }),
        ).catch((e) => (error = e.message))
      "
      >导出运营报表</el-button
    ></PageHeader
  ><el-alert v-if="error" :title="error" type="error" :closable="false" /><el-tabs
    ><el-tab-pane label="自定义报表"
      ><el-card
        ><div class="form-grid">
          <el-form-item label="统计维度"
            ><el-select v-model="q.dimension" aria-label="报表维度"
              ><el-option
                v-for="[value, label] in [
                  ['account', '子账号'],
                  ['group', '组'],
                  ['line_group', '线路组'],
                  ['city', '城市'],
                  ['script', '话术'],
                  ['campaign', '任务'],
                  ['agent', '坐席'],
                  ['line', '线路'],
                  ['owner', '负责人'],
                  ['flow_version', '话术流程版本'],
                ]"
                :key="value"
                :value="value"
                :label="label" /></el-select></el-form-item
          ><el-form-item label="时间粒度"
            ><el-select v-model="q.granularity" aria-label="报表粒度"
              ><el-option label="每分钟" value="minute" /><el-option
                label="汇总"
                value="all" /><el-option label="每天" value="day" /><el-option
                label="每小时"
                value="hour" /><el-option label="每月" value="month" /></el-select></el-form-item
          ><el-form-item label="开始"
            ><el-input
              v-model="q.from"
              aria-label="报表开始"
              placeholder="2026-10-01T00:00:00+08:00" /></el-form-item
          ><el-form-item label="结束"
            ><el-input
              v-model="q.until"
              aria-label="报表结束"
              placeholder="2026-10-31T23:59:59+08:00" /></el-form-item
          ><el-form-item label="任务"
            ><el-input v-model="q.campaign_id" aria-label="报表任务" /></el-form-item
          ><el-form-item label="话术流程版本编号"
            ><el-input
              v-model="q.flow_version_id"
              aria-label="报表话术流程版本编号" /></el-form-item
          ><el-form-item label="最小通话秒数"
            ><el-input v-model="q.min_seconds" aria-label="报表最小通话秒数" /></el-form-item
          ><el-form-item label="最大通话秒数"
            ><el-input v-model="q.max_seconds" aria-label="报表最大通话秒数" /></el-form-item
          ><el-form-item label="坐席组"
            ><el-input v-model="q.team_id" aria-label="报表坐席组"
          /></el-form-item>
        </div>
        <div class="toolbar">
          <el-input
            v-model="q.min_intent_rate"
            aria-label="意向率下限"
            placeholder="最低意向率（%）"
            style="width: 170px"
          /><el-input
            v-model="q.max_intent_rate"
            aria-label="意向率上限"
            placeholder="最高意向率（%）"
            style="width: 170px"
          />
        </div>
        <div v-for="(label, key) in rangeKinds" :key="key" class="toolbar">
          <el-button @click="addRange(key)" :disabled="rangesFor(key).length >= 8"
            >增加{{ label }}时长区间</el-button
          >
          <span v-for="(range, index) in rangesFor(key)" :key="index">
            <el-input-number
              :model-value="Number(range[0])"
              :aria-label="label + '最小秒数' + (index + 1)"
              :min="0"
              :max="86400"
              :precision="0"
              @update:model-value="(v) => setRange(key, index, 0, v ?? 0)"
            />
            至
            <el-input-number
              :model-value="Number(range[1])"
              :aria-label="label + '最大秒数' + (index + 1)"
              :min="0"
              :max="86400"
              :precision="0"
              @update:model-value="(v) => setRange(key, index, 1, v ?? 0)"
            />
            秒
            <el-button link @click="removeRange(key, index)"
              >移除{{ label }}区间{{ index + 1 }}</el-button
            >
          </span>
        </div>
        <p>同类区间满足任一即可，不同时长类型同时生效。未记录时长的会话不命中区间。</p>
        <el-button type="primary" :loading="busy" @click="load">生成报表</el-button>
        <div class="toolbar">
          <el-select v-model="viewId" aria-label="报表常用视图" @change="choose"
            ><el-option
              v-for="v in views.filter((v) => v.kind === 'reports')"
              :key="v.id"
              :value="v.id"
              :label="v.name" /></el-select
          ><el-input v-model="name" placeholder="视图名称" aria-label="报表视图名称" /><el-button
            :disabled="!name"
            @click="save"
            >保存报表视图</el-button
          >
        </div>
        <el-checkbox-group v-model="visible"
          ><el-checkbox v-for="(label, key) in labels" :key="key" :value="key">{{
            label
          }}</el-checkbox></el-checkbox-group
        ></el-card
      ><el-card header="通话时长分布">
        <el-radio-group v-model="durationKind" @change="loadDurations" aria-label="通话时长类型">
          <el-radio-button value="ai">AI</el-radio-button
          ><el-radio-button value="human">人工</el-radio-button
          ><el-radio-button value="total">总时长</el-radio-button>
        </el-radio-group>
        <el-alert v-if="durationError" type="error" :title="durationError" />
        <p>
          接通总数 {{ durationTotal }}。每通按会话累计，分段含右端点。无对应时长的接通也计入分母。
        </p>
        <p v-for="(label, bucket) in durationLabels" :key="bucket">
          {{ label }} · {{ durationCount(bucket) }} 通 ·
          {{
            durationTotal ? ((durationCount(bucket) * 100) / durationTotal).toFixed(2) + '%' : '—'
          }}
        </p>
      </el-card>
      <el-card header="会话分布"
        ><p>{{ definition }}</p>
        <div
          v-for="row in rows.slice(0, 20)"
          :key="row.bucket + ':' + row.dimension_key"
          class="ops-bar"
        >
          <button @click="drill(row)">{{ row.bucket }} · {{ row.dimension_key }}</button
          ><span :style="{ width: Math.max(1, ((row.sessions || 0) / max) * 55) + '%' }" />{{
            row.sessions
          }}
        </div>
        <el-empty v-if="!rows.length" description="没有符合条件的数据" /></el-card
      ><el-card
        ><DataGrid
          :rows="rows"
          :columns="columns"
          row-key="dimension_key"
          :pagination="20"
          :loading="busy"
          ><template #actions="{ row }"
            ><el-button link @click="drill(row)">下钻话单</el-button></template
          ></DataGrid
        ></el-card
      ></el-tab-pane
    ><el-tab-pane v-if="profile.administrator" label="任务运营台"
      ><el-card
        ><DataGrid
          :rows="tasks"
          :pagination="10"
          :columns="[
            { key: 'name', label: '任务' },
            { key: 'status', label: '状态', translate: true },
            { key: 'contacts', label: '导入人数' },
            { key: 'dialed_phones', label: '已拨号码' },
            { key: 'attempts', label: '累计尝试' },
            { key: 'queued', label: '排队中' },
            { key: 'waiting', label: '待转人工' },
            { key: 'failed', label: '失败会话' },
          ]"
          ><template #actions="{ row }"
            ><el-button link @click="task = row">运营详情</el-button
            ><el-button
              link
              @click="
                router.push({
                  path: prefix() + '/operations/calls',
                  query: { campaign_id: row.id },
                })
              "
              >任务话单</el-button
            ><el-button link @click="router.push('/admin/campaigns')">管理任务</el-button></template
          ></DataGrid
        ></el-card
      ></el-tab-pane
    ><el-tab-pane v-if="profile.administrator" label="费用与用量"><Billing /></el-tab-pane></el-tabs
  ><el-drawer :model-value="!!task" title="任务运营详情" @close="task = null"
    ><template v-if="task"
      ><el-descriptions :column="1" border
        ><el-descriptions-item
          v-for="(label, key) in {
            name: '任务',
            concurrency: '配置并发',
            contacts: '导入人数',
            answer_rate: '接通率（%）',
            entry_rate: '进线率（%）',
            takeover_rate: '接管率（%）',
            not_taken: '已结束未接管数',
            successful_calls: '合格成功数',
            successful_takeovers: '接管后成功数',
            success_per_answer: '接通成功率（%）',
            success_per_takeover: '接管成功率（%）',
            intent_rate: '意向率（%）',
            statement_cost: '账单话费（元）',
            bill_seconds: '账单计费时长（秒）',
            settled_attempts: '已对账拨打次数',
            sessions: '会话数',
            first_answered_attempts: '首呼接通数',
            repeat_answered_attempts: '复呼接通数',
            answered_attempts: '有接通回执的拨打次数',
            telephony_seconds: '有记录的通话时长（秒）',
            ai_seconds: '有记录的AI时长（秒）',

            attempts: '累计尝试',
            dialed_phones: '已拨号码',
            queued: '排队',
            waiting: '待人工',
            in_human: '人工通话',
            failed: '失败会话',
          }"
          :key="key"
          :label="label"
          >{{ task[key] ?? 0 }}</el-descriptions-item
        ></el-descriptions
      >
      <p>当前状态不代表历史接通过程；并发上限和真实线路承载需分别验收。</p>
      <el-button
        @click="
          router.push({ path: prefix() + '/operations/calls', query: { campaign_id: task.id } })
        "
        >查看明细与异常</el-button
      ></template
    ></el-drawer
  >
</template>
<style scoped>
.ops-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 10px 0;
}
.ops-bar button {
  min-width: 180px;
  text-align: left;
  border: 0;
  background: none;
  color: #2563eb;
  cursor: pointer;
}
.ops-bar span {
  height: 16px;
  background: #478bd4;
  border-radius: 3px;
}
</style>
