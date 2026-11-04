<script setup>
import { computed, ref, reactive, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useData, action } from '../data';
import { session, request } from '../session';
import { query, prefix } from '../operations';
const route = useRoute(),
  router = useRouter(),
  tab = ref('quick');
const date = (offset = 0) =>
  new Date(Date.now() + 8 * 3600000 + offset * 86400000).toISOString().slice(0, 10);
const q = reactive({
  from: date(),
  until: date(),
  campaign_id: '',
  agent_id: '',
  team_id: '',
  task_status: '',
  script_id: '',
});
const applied = ref({ ...q }),
  error = ref('');
function search() {
  if (!q.from || !q.until || q.from > q.until) {
    error.value = '请选择正确的起止日期';
    return;
  }
  error.value = '';
  applied.value = { ...q };
  summary.load();
  tasks.load();
  series.load();
}
function path(dim, grain) {
  const a = applied.value;
  return (
    '/api/v1/ops/reports?' +
    query({
      ...a,
      from: a.from + 'T00:00:00+08:00',
      until: a.until + 'T23:59:59.999+08:00',
      dimension: dim,
      granularity: grain,
    })
  );
}
const summary = useData(() => path('all', 'all')),
  tasks = useData(() => path('campaign', 'all')),
  series = useData(() => (tab.value === 'script' ? path('script', 'minute') : ''));
const scripts = useData('/api/v1/workspace/scripts?size=200'),
  users = useData('/api/v1/ops/me'),
  teams = useData('/api/v1/ops/teams'),
  campaigns = useData('/api/v1/workspace/tasks?size=200');
const row = computed(() => summary.data.value?.rows?.[0] || {}),
  metrics = {
    answer: ['接通率', 'answered_attempts', 'attempts'],
    entry: ['进线率', 'handoff_requests', 'reached_sessions'],
    not_taken: ['未接管率', 'not_taken', 'handoff_requests'],
    loss: ['呼损率', 'loss_count', 'handoff_requests'],
    intent: ['AB意向率', 'ab_intents', 'reached_sessions'],
    success: ['接通成功率', 'successful_calls', 'reached_sessions'],
    taken: ['接管成功率', 'successful_takeovers', 'handoff_received'],
    wait: ['坐席平均等待', 'wait_total', 'wait_samples'],
    recent: ['近5分钟等待', 'recent_wait_total', 'recent_wait_samples'],
  };
const pref = 'outbound-metrics:' + session.user?.tenant_id + ':' + session.user?.id;
let saved;
try {
  saved = JSON.parse(localStorage.getItem(pref));
} catch {}
const shown = ref(Array.isArray(saved) ? saved.filter((k) => metrics[k]) : Object.keys(metrics));
watch(shown, (v) => localStorage.setItem(pref, JSON.stringify(v)), { deep: true });
function rate(a, b) {
  return Number(b) ? Number(((100 * Number(a || 0)) / Number(b)).toFixed(2)) : null;
}
function shownValue(k) {
  const [, n, d] = metrics[k],
    r = row.value;
  if (k === 'wait' || k === 'recent')
    return Number(r[d]) ? (Number(r[n]) / r[d]).toFixed(1) + ' 秒' : '—';
  const v = rate(r[n], r[d]);
  return v == null ? '—' : v.toFixed(2) + '%';
}
const cols = {
  attempts: '外呼数',
  answers: '接通数（率）',
  entry: '进线数（率）',
  loss_count: '呼损数',
  not_taken: '未接管数',
  taken: '接管数（率）',
  successful_calls: '成功数',
  success_per_answer: '接通成功率',
  success_per_takeover: '接管成功率',
};
const enabledCols = ref(Object.keys(cols)),
  density = ref('default'),
  picked = ref([]),
  bounds = reactive({
    success_per_answer: ['', ''],
    success_per_takeover: ['', ''],
    entry_rate: ['', ''],
  });
const rows = computed(() =>
  (tasks.data.value?.rows || []).filter((r) =>
    Object.entries(bounds).every(
      ([key, [a, b]]) =>
        (a === '' || (r[key] != null && Number(r[key]) >= Number(a))) &&
        (b === '' || (r[key] != null && Number(r[key]) <= Number(b))),
    ),
  ),
);
function val(r, k) {
  const pair = {
    answers: ['answered_attempts', 'answer_rate'],
    entry: ['handoff_requests', 'entry_rate'],
    taken: ['handoff_received', 'takeover_rate'],
  }[k];
  return pair
    ? (r[pair[0]] ?? 0) + '（' + (r[pair[1]] == null ? '—' : r[pair[1]] + '%') + '）'
    : (r[k] ?? '—');
}
function drill(r) {
  const a = applied.value;
  router.push({
    path: prefix() + '/operations/calls',
    query: {
      ...Object.fromEntries(Object.entries(a).filter(([, v]) => v)),
      campaign_id: r.dimension_key,
      from: a.from + 'T00:00:00+08:00',
      until: a.until + 'T23:59:59.999+08:00',
    },
  });
}
async function command(r, cmd) {
  await action(() => request('/api/v1/campaigns/' + r.dimension_key + '/' + cmd, 'POST'), {
    confirm: cmd === 'stop' ? '终止这个任务并停止后续拨打？' : '启动任务并使用其已配置线路？',
    refresh: search,
  });
}
function saveFile(text, mime, name) {
  const url = URL.createObjectURL(new Blob([text], { type: mime }));
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
async function exportTasks() {
  if (!users.data.value?.can_export) return;
  try {
    await request(path('campaign', 'all').replace('/reports?', '/reports/export?'));
  } catch (e) {
    error.value = e.message;
    return;
  }
  const selectedIds = new Set(picked.value.map((r) => r.dimension_key));
  const data = [
    ['任务名', ...enabledCols.value.map((k) => cols[k])],
    ...rows.value
      .filter((r) => !selectedIds.size || selectedIds.has(r.dimension_key))
      .map((r) => [r.campaign_name, ...enabledCols.value.map((k) => val(r, k))]),
  ];
  saveFile(
    '\uFEFF' +
      data
        .map((r) =>
          r
            .map(
              (x) =>
                '"' +
                String(x)
                  .replace(/^[=+@\-\r\t]/, "'$&")
                  .replaceAll('"', '""') +
                '"',
            )
            .join(','),
        )
        .join('\r\n'),
    'text/csv;charset=utf-8',
    '任务呼叫统计.csv',
  );
}
const seriesMetrics = {
  answer: ['接通', 'answered_attempts', 'attempts'],
  entry: ['触发进线', 'handoff_requests', 'reached_sessions'],
  popup: ['弹屏', 'screen_count', 'handoff_requests'],
  take: ['接管', 'handoff_received', 'handoff_requests'],
  lost: ['呼损', 'loss_count', 'handoff_requests'],
  short: ['15秒内挂机', 'short_answers', 'measured_answers'],
  shortTake: ['接管5秒挂机', 'short_taken', 'measured_taken'],
  success: ['接通成功', 'successful_calls', 'reached_sessions'],
};
const mode = ref('全指标（比率）'),
  metric = ref('answer'),
  zoom = ref([0, 100]),
  svg = ref();
const chart = computed(() => {
  const list = series.data.value?.rows || [],
    times = [...new Set(list.map((r) => r.bucket))].sort();
  const shownTimes = times.slice(
    Math.floor((times.length * zoom.value[0]) / 100),
    Math.max(1, Math.ceil((times.length * zoom.value[1]) / 100)),
  );
  const groups = new Map(),
    single = mode.value.startsWith('单指标'),
    percent = mode.value.includes('比率');
  for (const r of list) {
    for (const k of single ? [metric.value] : Object.keys(seriesMetrics)) {
      const label = single ? '话术 ' + r.dimension_key : seriesMetrics[k][0];
      if (!groups.has(label)) groups.set(label, new Map());
      const g = groups.get(label),
        v = g.get(r.bucket) || [0, 0];
      v[0] += Number(r[seriesMetrics[k][1]] || 0);
      v[1] += Number(r[seriesMetrics[k][2]] || 0);
      g.set(r.bucket, v);
    }
  }
  const lines = [...groups].map(([label, g]) => ({
    label,
    values: shownTimes.map((t) => {
      const v = g.get(t);
      return !v ? null : percent ? rate(v[0], v[1]) : v[0];
    }),
  }));
  const max = percent
    ? 100
    : Math.max(1, ...lines.map((l) => l.values.reduce((a, x) => Math.max(a, x || 0), 0)));
  return { times: shownTimes, lines, max };
});
const colors = [
  '#1769aa',
  '#b45d00',
  '#248860',
  '#a33b71',
  '#7149ae',
  '#706717',
  '#007b86',
  '#a93528',
];
const x = (i, n) => 35 + (i * 700) / Math.max(1, n - 1),
  y = (v) => 190 - (v * 165) / chart.value.max;
function linePath(values) {
  let start = true;
  return values
    .map((v, i) => {
      if (v == null) {
        start = true;
        return '';
      }
      const d = (start ? 'M' : 'L') + x(i, values.length) + ' ' + y(v);
      start = false;
      return d;
    })
    .join(' ');
}
function downloadChart() {
  if (svg.value)
    saveFile(new XMLSerializer().serializeToString(svg.value), 'image/svg+xml', '话术指标.svg');
}
</script>
<template>
  <PageHeader title="外呼运营面板" description="筛选条件同时作用于汇总、任务明细和话术趋势。" />
  <el-card
    ><div class="toolbar">
      <label>开始日期 <input aria-label="面板开始日期" type="date" v-model="q.from" /></label
      ><label>结束日期 <input aria-label="面板结束日期" type="date" v-model="q.until" /></label>
      <el-select
        v-model="q.campaign_id"
        clearable
        aria-label="选择任务"
        placeholder="全部任务"
        style="width: 180px"
        ><el-option
          v-for="c in campaigns.data.value?.rows || []"
          :key="c.id"
          :value="c.id"
          :label="c.name"
      /></el-select>
      <el-select
        v-model="q.team_id"
        clearable
        aria-label="选择坐席组"
        placeholder="全部坐席组"
        style="width: 150px"
        ><el-option v-for="t in teams.data.value || []" :key="t.id" :value="t.id" :label="t.name"
      /></el-select>
      <el-select
        v-model="q.agent_id"
        clearable
        aria-label="选择坐席"
        placeholder="全部坐席"
        style="width: 150px"
        ><el-option
          v-for="u in users.data.value?.users || []"
          :key="u.id"
          :value="u.id"
          :label="u.username"
      /></el-select>
      <el-select
        v-model="q.script_id"
        clearable
        aria-label="选择话术"
        placeholder="全部话术"
        style="width: 175px"
        ><el-option
          v-for="s in scripts.data.value?.rows || []"
          :key="s.id"
          :value="s.id"
          :label="s.name || s.draft.name"
      /></el-select>
      <el-button @click="search">查询面板</el-button><el-button @click="search">刷新面板</el-button>
    </div></el-card
  >
  <el-alert
    v-if="error || summary.error.value || tasks.error.value || series.error.value"
    :title="error || summary.error.value || tasks.error.value || series.error.value"
    type="error"
  />
  <el-tabs v-model="tab"
    ><el-tab-pane name="quick" label="快捷面板" /><el-tab-pane
      name="tasks"
      label="任务呼叫统计" /><el-tab-pane name="script" label="话术指标"
  /></el-tabs>
  <template v-if="tab === 'quick'"
    ><el-popover trigger="click" width="250"
      ><template #reference><el-button>面板设置</el-button></template
      ><el-checkbox-group v-model="shown"
        ><el-checkbox v-for="(v, k) in metrics" :value="k" :key="k">{{
          v[0]
        }}</el-checkbox></el-checkbox-group
      ></el-popover
    >
    <div class="cards">
      <el-card v-for="k in shown" :key="k"
        ><strong>{{ metrics[k][0] }}</strong>
        <h2>{{ shownValue(k) }}</h2>
        <small>{{ row[metrics[k][1]] ?? 0 }} / {{ row[metrics[k][2]] ?? 0 }}</small></el-card
      >
    </div>
    <p>
      呼损记录等待或桥接中结束的会话，分母为进线会话。未接管只计已结束会话。等待按已接管请求的请求至响应时间统计；近5分钟以响应时刻归属。
    </p></template
  >
  <template v-if="tab === 'tasks'"
    ><el-card
      ><div class="toolbar">
        <el-select
          v-model="q.task_status"
          aria-label="任务状态筛选"
          clearable
          placeholder="全部状态"
          style="width: 140px"
          ><el-option
            v-for="[k, l] in [
              ['running', '运行中'],
              ['completed', '已完成'],
              ['stopped', '已终止'],
              ['paused', '已暂停'],
              ['draft', '草稿'],
            ]"
            :key="k"
            :value="k"
            :label="l"
        /></el-select>
        <span v-for="(v, k) in bounds" :key="k"
          >{{ cols[k] || '进线率' }}
          <input type="number" min="0" max="100" v-model="v[0]" :aria-label="k + '下限'" /> 至
          <input type="number" min="0" max="100" v-model="v[1]" :aria-label="k + '上限'" />%</span
        >
      </div>
      <el-select v-model="density" aria-label="任务表密度" style="width: 110px"
        ><el-option label="标准" value="default" /><el-option
          label="紧凑"
          value="small" /><el-option label="宽松" value="large"
      /></el-select>
      <el-popover trigger="click" width="250"
        ><template #reference><el-button>任务列设置</el-button></template
        ><el-checkbox-group v-model="enabledCols"
          ><el-checkbox v-for="(v, k) in cols" :key="k" :value="k">{{
            v
          }}</el-checkbox></el-checkbox-group
        ></el-popover
      ><el-button v-if="users.data.value?.can_export" @click="exportTasks">导出任务统计</el-button>
      <el-table
        :data="rows"
        row-key="dimension_key"
        :size="density"
        @selection-change="picked = $event"
        ><el-table-column type="selection" /><el-table-column
          label="任务名"
          prop="campaign_name"
          sortable
        /><el-table-column
          v-for="k in enabledCols"
          :key="k"
          :prop="
            { answers: 'answered_attempts', entry: 'handoff_requests', taken: 'handoff_received' }[
              k
            ] || k
          "
          :label="cols[k]"
          sortable
          min-width="130"
          ><template #default="{ row: r }"
            ><el-button v-if="k === 'attempts'" link @click="drill(r)">{{ val(r, k) }}</el-button
            ><span v-else>{{ val(r, k) }}</span></template
          ></el-table-column
        ><el-table-column label="操作" fixed="right"
          ><template #default="{ row: r }"
            ><el-button
              v-if="['draft', 'stopped', 'failed', 'prepared'].includes(r.campaign_status)"
              link
              @click="command(r, 'start')"
              >开始</el-button
            ><el-button
              v-if="['running', 'paused'].includes(r.campaign_status)"
              link
              @click="command(r, 'stop')"
              >终止</el-button
            ></template
          ></el-table-column
        ></el-table
      ></el-card
    ></template
  >
  <template v-if="tab === 'script'"
    ><el-card
      ><el-radio-group v-model="mode"
        ><el-radio-button
          v-for="v in ['全指标（比率）', '单指标对比（比率）', '全指标（数）', '单指标对比（数）']"
          :key="v"
          :value="v"
          >{{ v }}</el-radio-button
        ></el-radio-group
      >
      <el-select
        v-if="mode.startsWith('单指标')"
        v-model="metric"
        aria-label="对比指标"
        style="width: 170px"
        ><el-option v-for="(v, k) in seriesMetrics" :key="k" :value="k" :label="v[0]" /></el-select
      ><el-button @click="downloadChart" :disabled="!chart.times.length">下载图表</el-button>
      <svg
        v-if="chart.times.length"
        ref="svg"
        xmlns="http://www.w3.org/2000/svg"
        viewBox="0 0 760 245"
        style="width: 100%"
      >
        <title>话术指标 {{ mode }}</title>
        <rect width="760" height="245" fill="white" />
        <text x="8" y="22" font-size="12">
          {{ chart.max }}{{ mode.includes('比率') ? '%' : '' }}
        </text>
        <path d="M35 25 V190 H740" stroke="#999" fill="none" />
        <g v-for="(l, i) in chart.lines" :key="l.label">
          <path :d="linePath(l.values)" :stroke="colors[i % 8]" fill="none" stroke-width="2" />
          <template v-for="(v, j) in l.values" :key="j">
            <circle
              v-if="v != null"
              :cx="x(j, l.values.length)"
              :cy="y(v)"
              r="3"
              :fill="colors[i % 8]"
            >
              <title>{{ l.label }} · {{ chart.times[j] }} · {{ v }}</title>
            </circle>
          </template>
          <text
            :x="34 + (i % 4) * 178"
            :y="218 + Math.floor(i / 4) * 15"
            font-size="12"
            :fill="colors[i % 8]"
          >
            {{ l.label }}
          </text>
        </g>
      </svg>
      <el-empty v-else description="没有符合条件的话术指标" />
      <div>{{ chart.times[0] || '—' }} 至 {{ chart.times.at(-1) || '—' }}（UTC）</div>
      <el-slider v-model="zoom" range :min="0" :max="100" :step="1" aria-label="时间轴缩放" />
      <p>
        15秒内挂机按已接通且有总时长的尝试计算，接管5秒挂机按桥接起止都已记录的接管计算。无有效分母时不绘制比率。
      </p></el-card
    ></template
  >
</template>
<style scoped>
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 10px;
  margin-top: 12px;
}
input[type='number'] {
  width: 65px;
}
input {
  padding: 4px;
}
</style>
