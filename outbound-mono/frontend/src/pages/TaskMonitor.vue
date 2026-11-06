<script setup>
const crypto = globalThis.crypto;
const statusLabels = {
  draft: '草稿',
  running: '运行中',
  prepared: '待启动',
  paused: '已暂停',
  stopped: '已终止',
  completed: '已完成',
  failed: '失败',
};
import { computed, ref, reactive } from 'vue';
import { useRoute } from 'vue-router';
import { useData } from '../data';
import { campaignCommand, query, perform, ops } from '../operations';
const route = useRoute(),
  history = computed(() => route.path.endsWith('/history'));
const q = reactive({
    name: '',
    script_id: '',
    owner_id: '',
    mode: '',
    line_id: '',
    from: '',
    until: '',
    retry_limit: '',
    team_id: '',
    evaluation_rule: '',
    suggestion: '',
    operation_type: '',
    min_seconds: '',
    max_seconds: '',
    page: 1,
  }),
  applied = ref({ ...q }),
  selectedId = ref(null);
const data = useData(
    () => '/api/v1/ops/benchmark/tasks?' + query({ ...applied.value, history: history.value }),
    15000,
  ),
  profile = useData('/api/v1/ops/me'),
  scripts = useData('/api/v1/workspace/scripts?size=200'),
  settings = useData('/api/v1/ops/benchmark/task-settings');
const rows = computed(() => data.data.value?.rows || []),
  selected = computed(() => rows.value.find((r) => r.id === selectedId.value) || rows.value[0]),
  visible = ref([
    'id',
    'name',
    'script_name',
    'line_names',
    'team_names',
    'contacts',
    'dialed_contacts',
    'answered',
    'evaluation_rule',
    'taken',
    'sms',
    'seconds',
    'bill_seconds',
    'answer_rate',
    'takeover_rate',
    'entry_rate',
    'success_rate',
    'interested',
    'progress',
    'started_at',
    'ended_at',
    'created_by',
    'retry_limit',
  ]);
const cols = {
  line_names: '线路',
  team_names: '坐席组',
  evaluation_rule: '评估规则',
  evaluation_result: '评估结果',
  answer_rate: '接通率（%）',
  takeover_rate: '接管率（%）',
  entry_rate: '进线率（%）',
  success_rate: '接通成功率（%）',
  progress: '进度（%）',
  started_at: '开始时间',
  ended_at: '结束时间',
  id: 'ID',
  name: '任务名称',
  script_name: '话术',
  mode: '任务类型',
  contacts: '总数',
  dialed_contacts: '已拨打',
  dialed: '累计拨打尝试',
  answered: '接通次数',
  connected_sessions: '接通会话数',
  taken: '接管数',
  sms: '短信数',
  seconds: '通话时长(s)',
  bill_seconds: '计费时长',
  status: '状态',
  created_at: '开始时间',
  updated_at: '结束时间',
  created_by: '任务所属',
  retry_limit: '重拨类型',
  interested: '有效意向',
  cost: '通话费用',
};
const trend = useData(() =>
  selected.value ? '/api/v1/ops/reports/daily?campaign_id=' + selected.value.id : '',
);
const distribution = computed(() => {
  const m = new Map();
  for (const r of trend.data.value || [])
    m.set(r.intent_key, (m.get(r.intent_key) || 0) + Number(r.sessions || 0));
  return [...m.entries()].map(([intent, total]) => ({ intent, total }));
});
function search() {
  const p = { ...q };
  for (const k of ['from', 'until']) if (p[k]) p[k] = new Date(p[k]).toISOString();
  applied.value = p;
  data.load();
}
const percent = (a, b) => (Number(b) ? ((100 * Number(a || 0)) / Number(b)).toFixed(2) + '%' : '—');
async function command(row, cmd) {
  await perform(() => campaignCommand(row.id, cmd), data.load);
}
function progress(r) {
  return Number(r.contacts) ? Math.min(100, (100 * Number(r.finished || 0)) / r.contacts) : 0;
}
</script>
<template>
  <PageHeader
    :title="history ? '历史任务' : '当前任务'"
    description="选择任务查看外呼进度、意向结果与成本"
    ><el-button
      type="primary"
      @click="$router.push({ path: '/admin/campaigns', query: { create: '1' } })"
      >创建任务</el-button
    ><el-button @click="data.load">刷新</el-button
    ><el-button
      v-if="history"
      @click="
        perform(() =>
          ops('/exports', 'POST', {
            kind: 'tasks',
            query: { ...applied, history: true },
            request_key: crypto.randomUUID(),
          }),
        )
      "
      >导出</el-button
    ></PageHeader
  >
  <el-alert v-if="data.error.value" :title="data.error.value" type="error" /><el-card
    ><el-form class="form-grid" label-position="top"
      ><el-form-item label="任务名称"
        ><el-input v-model="q.name" aria-label="任务查询名称" /></el-form-item
      ><template v-if="history"
        ><el-form-item label="话术"
          ><el-select v-model="q.script_id" clearable aria-label="历史话术"
            ><el-option
              v-for="s in scripts.data.value?.rows || []"
              :key="s.id"
              :label="s.name"
              :value="s.id" /></el-select></el-form-item
        ><el-form-item label="任务所属"
          ><el-select v-model="q.owner_id" clearable aria-label="历史任务所属"
            ><el-option
              v-for="u in profile.data.value?.users || []"
              :key="u.id"
              :value="u.id"
              :label="u.full_name" /></el-select></el-form-item
        ><el-form-item
          v-for="[key, label] in [
            ['from', '开始时间'],
            ['until', '结束时间'],
          ]"
          :key="key"
          :label="label"
          ><el-input
            v-model="q[key]"
            type="datetime-local"
            :aria-label="'历史' + label" /></el-form-item
        ><el-form-item
          v-for="[key, label] in [
            ['min_seconds', '最小通话时长'],
            ['max_seconds', '最大通话时长'],
            ['retry_limit', '重拨次数'],
            ['line_id', '选择线路'],
          ]"
          :key="key"
          :label="label"
          ><el-input v-model="q[key]" :aria-label="'历史' + label" /></el-form-item
        ><el-form-item label="评估规则"
          ><el-input
            v-model="q.evaluation_rule"
            aria-label="历史评估规则"
            placeholder="话术评级规则标签或ID" /></el-form-item
        ><el-form-item label="外呼建议"
          ><el-select v-model="q.suggestion" clearable aria-label="历史外呼建议"
            ><el-option
              v-for="[v, l] in [
                ['insufficient', '数据不足'],
                ['improve', '优化话术'],
                ['continue', '继续拨打'],
              ]"
              :key="v"
              :label="l"
              :value="v" /></el-select></el-form-item
        ><el-form-item label="坐席组"
          ><el-input v-model="q.team_id" aria-label="历史坐席组" /></el-form-item
        ><el-form-item label="操作类型"
          ><el-select v-model="q.operation_type" clearable aria-label="历史操作类型"
            ><el-option
              v-for="[v, l] in [
                ['completed', '完成'],
                ['stopped', '终止'],
                ['failed', '失败'],
              ]"
              :key="v"
              :value="v"
              :label="l" /></el-select></el-form-item
        ><el-form-item label="任务类型"
          ><el-select v-model="q.mode" clearable aria-label="历史任务类型"
            ><el-option
              v-for="[v, l] in [
                ['ai_only', 'AI首呼'],
                ['ai_handoff', 'AI转人工'],
                ['human_only', '人工拨号'],
                ['ai_with_sms', 'AI与短信'],
              ]"
              :key="v"
              :value="v"
              :label="l" /></el-select></el-form-item></template></el-form
    ><el-button
      type="primary"
      @click="
        q.page = 1;
        search();
      "
      >查询</el-button
    ></el-card
  >
  <div v-if="!history" class="task-monitor">
    <el-card
      ><el-empty v-if="!rows.length" description="暂无当前任务" /><button
        v-for="r in rows"
        :key="r.id"
        class="task-choice"
        :aria-pressed="selected?.id === r.id"
        @click="selectedId = r.id"
      >
        <strong>{{ r.name }}</strong
        ><span>{{ statusLabels[r.status] || r.status }}</span>
        <p>{{ r.created_at }}</p>
        <span>成本 {{ r.cost ?? '—' }} 元</span
        ><el-progress :percentage="progress(r)" :show-text="false" /></button></el-card
    ><el-card v-if="selected" :header="selected.name"
      ><div class="metric-grid">
        <div
          v-for="[label, value] in [
            ['接通率', percent(selected.answered, selected.dialed)],
            ['进度', progress(selected).toFixed(1) + '%'],
            [
              '剩余数量',
              Math.max(0, Number(selected.contacts || 0) - Number(selected.dialed_contacts || 0)),
            ],
            ['意向率', percent(selected.interested, selected.connected_sessions)],
            ['接管率', percent(selected.taken, selected.entered)],
            ['通话时长', selected.seconds ?? '—'],
          ]"
          :key="label"
        >
          <small>{{ label }}</small>
          <h2>{{ value }}</h2>
        </div>
      </div>
      <el-progress :percentage="progress(selected)" />
      <h3>意向统计</h3>
      <el-table :data="distribution" size="small"
        ><el-table-column prop="intent" label="意向" /><el-table-column prop="total" label="数量"
      /></el-table>
      <h3>每日呼叫趋势</h3>
      <div
        v-for="point in (trend.data.value || []).slice(-15)"
        :key="point.day_key + point.intent_key"
        class="toolbar"
      >
        <span>{{ point.day_key }} · {{ point.intent_key }}</span
        ><el-progress
          style="width: 55%"
          :percentage="
            Number(point.sessions)
              ? Math.round((100 * Number(point.answered_sessions || 0)) / point.sessions)
              : 0
          "
        /><span>{{ point.attempts }} 次外呼</span>
      </div>
      <div class="toolbar">
        <el-button
          v-if="['draft', 'prepared', 'stopped', 'failed'].includes(selected.status)"
          type="primary"
          @click="command(selected, 'start')"
          >启动</el-button
        ><el-button v-if="selected.status === 'running'" @click="command(selected, 'pause')"
          >暂停</el-button
        ><el-button v-if="selected.status === 'paused'" @click="command(selected, 'resume')"
          >恢复</el-button
        ><el-button
          v-if="['running', 'paused', 'prepared'].includes(selected.status)"
          type="danger"
          @click="command(selected, 'stop')"
          >停止</el-button
        ><el-button
          @click="
            $router.push({ path: '/admin/tasks/record', query: { campaign_id: selected.id } })
          "
          >查看话单</el-button
        ><el-button
          @click="
            $router.push({
              path: '/admin/operations/performance',
              query: { campaign_id: selected.id },
            })
          "
          >实时统计</el-button
        >
      </div></el-card
    >
  </div>
  <el-card v-else
    ><el-popover trigger="click" width="320"
      ><template #reference><el-button>列设置</el-button></template
      ><el-checkbox-group v-model="visible"
        ><el-checkbox v-for="(label, key) in cols" :key="key" :value="key">{{
          label
        }}</el-checkbox></el-checkbox-group
      ></el-popover
    ><el-table :data="rows" border
      ><el-table-column
        v-for="key in visible"
        :key="key"
        :prop="key"
        :label="cols[key]"
        min-width="145"
      /><el-table-column label="评估结果" width="160"
        ><template #default="{ row }">{{
          Number(row.connected_sessions)
            ? (100 * Number(row.interested || 0)) / Number(row.connected_sessions) >=
              Number(settings.data.value?.draft.target_intent_rate || 0)
              ? '已达目标'
              : '未达目标'
            : '数据不足'
        }}</template></el-table-column
      ><el-table-column label="操作" fixed="right" width="150"
        ><template #default="{ row }"
          ><el-button
            link
            @click="$router.push({ path: '/admin/tasks/record', query: { campaign_id: row.id } })"
            >查看话单</el-button
          ></template
        ></el-table-column
      ></el-table
    ></el-card
  >
  <el-pagination
    :current-page="q.page"
    :total="data.data.value?.total || 0"
    :page-size="20"
    layout="prev,pager,next,total"
    @current-change="
      q.page = $event;
      search();
    "
  />
</template>
<style scoped>
.task-monitor {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 20px;
}
.task-choice {
  display: block;
  width: 100%;
  padding: 18px;
  margin-bottom: 12px;
  text-align: left;
  border: 1px solid #dce5ef;
  border-radius: 8px;
  background: white;
  cursor: pointer;
}
.task-choice[aria-pressed='true'] {
  border-color: #2563eb;
  background: #eef5ff;
}
.task-choice > span {
  float: right;
}
@media (max-width: 900px) {
  .task-monitor {
    grid-template-columns: 1fr;
  }
}
</style>
