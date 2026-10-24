<script setup>
import { ref, reactive, onMounted, computed } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { ops, perform, parse, query, prefix } from '../operations';
import Billing from './Billing.vue';
import { download } from '../session';
import { usePageQuery } from '../page-query';
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
const q = reactive({ dimension: 'campaign', granularity: 'day', ...route.query }),
  visible = ref([
    'sessions',
    'attempts',
    'phones',
    'reached_sessions',
    'reached_phones',
    'handoff_requests',
    'handoff_received',
    'qualified_phones',
    'failed_sessions',
  ]);
const labels = {
  sessions: '会话数',
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
  const field = { campaign: 'campaign_id', agent: 'agent_id', line: 'line_id', owner: 'owner_id' }[
    q.dimension
  ];
  if (Number(row.dimension_key) > 0) f[field] = row.dimension_key;
  if (row.bucket !== '全部') {
    const date = new Date(
      row.bucket.replace(' ', 'T') + (row.bucket.length === 10 ? 'T00:00:00' : '') + 'Z',
    );
    f.from = date.toISOString();
    f.until = new Date(
      date.getTime() + (q.granularity === 'hour' ? 3600000 : 86400000) - 1,
    ).toISOString();
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
  <PageHeader title="运营报表" description="按任务、坐席、线路和负责人分析并下钻话单"
    ><el-button
      v-if="profile.can_export"
      @click="
        download('/api/v1/ops/reports/export?' + query(params())).catch((e) => (error = e.message))
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
                  ['campaign', '任务'],
                  ['agent', '坐席'],
                  ['line', '线路'],
                  ['owner', '负责人'],
                ]"
                :key="value"
                :value="value"
                :label="label" /></el-select></el-form-item
          ><el-form-item label="时间粒度"
            ><el-select v-model="q.granularity" aria-label="报表粒度"
              ><el-option label="汇总" value="all" /><el-option
                label="每天"
                value="day" /><el-option label="每小时" value="hour" /></el-select></el-form-item
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
          ><el-form-item label="坐席组"
            ><el-input v-model="q.team_id" aria-label="报表坐席组"
          /></el-form-item>
        </div>
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
      ><el-card header="会话分布"
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
            sessions: '会话数',
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
