<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { download, request } from '../session';
import { ops, perform, parse, query, prefix } from '../operations';
import CallDetails from '../components/CallDetails.vue';
import { usePageQuery } from '../page-query';
import { ElMessageBox } from 'element-plus';
const { isCurrent, replaceQuery } = usePageQuery();
const options = ref({ campaigns: [], lines: [], teams: [] });
const exportMode = ref(false),
  exportConfig = reactive({
    export_fields: 'calls',
    export_dialogue: 'both',
    export_split: 'day',
    export_count: 1000,
    export_types: ['undialed', 'unanswered', 'answered'],
    export_phones: '',
  });
async function uploadNumbers(event) {
  const file = event.target.files?.[0];
  if (!file) return;
  const body = new FormData();
  body.append('file', file);
  try {
    const result = await request('/api/v1/ops/exports/phones', 'POST', body);
    exportConfig.export_phones = result.phones.join(',');
  } catch (e) {
    error.value = e.message;
  }
  event.target.value = '';
}
const route = useRoute(),
  router = useRouter(),
  filters = reactive({}),
  rows = ref([]),
  total = ref(0),
  error = ref(''),
  busy = ref(false),
  selected = ref(null),
  profile = ref({}),
  views = ref([]),
  viewName = ref(''),
  viewId = ref('');
const allColumns = [
  { key: 'phone', label: '号码' },
  { key: 'campaign_name', label: '任务' },
  { key: 'status', label: '通话状态', translate: true },
  { key: 'intent', label: '意向' },
  { key: 'duration_seconds', label: '通话秒数' },
  { key: 'qa_score', label: '质检分数' },
  { key: 'review_state', label: '复核状态' },
  { key: 'owner_id', label: '负责人编号' },
  { key: 'stage', label: '跟进状态' },
  { key: 'created_at', label: '拨打时间', date: true },
  ...[
    ['call_cost', '通话费用（元）'],
    ['follow_at', '跟进时间'],
    ['remark', '备注'],
    ['assigned_at', '分配时间'],
    ['last_error', '挂机原因'],
    ['gender', '性别（已采集）'],
    ['grade', 'AI评级'],
    ['result_code', '成功单结果'],
    ['reviewed_by', '质检账号'],
  ].map(([key, label]) => ({ key, label })),
];
const visible = ref(allColumns.map((c) => c.key)),
  columns = computed(() => allColumns.filter((c) => visible.value.includes(c.key))),
  page = computed(() => Number(route.query.page || 1));
async function convert(row) {
  const lead = await perform(() => ops('/calls/' + row.id + '/lead', 'POST'));
  if (lead)
    await router.push({
      path: prefix() + '/operations/leads',
      query: { contact_id: lead.contact_id },
    });
}
function localTime(value) {
  if (!value || Number.isNaN(new Date(value).getTime())) return '';
  return new Date(new Date(value).getTime() - new Date(value).getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 16);
}
function fromRoute() {
  Object.keys(filters).forEach((k) => delete filters[k]);
  Object.assign(filters, route.query);
  for (const k of ['from', 'until']) filters[k] = localTime(route.query[k]);
}
async function load() {
  if (!isCurrent()) return;
  busy.value = true;
  error.value = '';
  try {
    const data = await ops('/calls?' + query({ ...route.query, size: 20 }));
    if (!isCurrent()) return;
    rows.value = data.rows;
    total.value = data.total;
  } catch (e) {
    error.value = e.message;
    rows.value = [];
    total.value = 0;
  } finally {
    busy.value = false;
  }
}
function apply() {
  const next = { ...filters, page: 1 };
  for (const k of ['from', 'until']) if (next[k]) next[k] = new Date(next[k]).toISOString();
  replaceQuery(Object.fromEntries(Object.entries(next).filter(([, v]) => v !== '' && v != null)));
}
function reset() {
  replaceQuery({});
}
async function saveView() {
  if (!viewName.value) return;
  await perform(
    () =>
      ops('/views', 'POST', {
        kind: 'calls',
        name: viewName.value,
        config: { filters: { ...route.query, page: 1 }, columns: visible.value },
      }),
    async () => (views.value = await ops('/views')),
  );
}
function chooseView() {
  const v = views.value.find((v) => v.id === viewId.value);
  if (!v) return;
  const c = parse(v.config_json);
  visible.value = Array.isArray(c.columns)
    ? c.columns.filter((k) => allColumns.some((x) => x.key === k))
    : allColumns.map((c) => c.key);
  replaceQuery(c.filters || {});
}
async function exportRows() {
  try {
    const security = await request('/api/v1/auth/security');
    let verification_code = '';
    if (security.config.export_verification) {
      try {
        const reply = await ElMessageBox.prompt('请输入验证器中的六位验证码', '导出验证', {
          inputPattern: /^[0-9]{6}$/,
          inputErrorMessage: '请输入六位验证码',
        });
        verification_code = reply.value;
      } catch {
        return;
      }
    }
    await ops('/exports', 'POST', {
      kind: exportMode.value ? 'calls-custom' : 'calls',
      query: {
        ...route.query,
        ...(exportMode.value
          ? { ...exportConfig, export_types: exportConfig.export_types.join(',') }
          : {}),
      },
      request_key: crypto.randomUUID(),
      verification_code,
    });
    await router.push(prefix() + '/operations/notices?tab=downloads');
  } catch (e) {
    error.value = e.message;
  }
}
watch(
  () => route.query,
  () => {
    if (!isCurrent()) return;
    fromRoute();
    load();
  },
  { deep: true },
);
onMounted(async () => {
  fromRoute();
  load();
  try {
    profile.value = await ops('/me');
    if (!isCurrent()) return;
    options.value = await ops('/lookups');
    if (!isCurrent()) return;
    views.value = await ops('/views');
  } catch (e) {
    error.value = e.message;
  }
});
</script>
<template>
  <PageHeader title="话单查询" description="组合筛选、查询总数和导出使用同一范围"
    ><el-button @click="router.push(prefix() + '/calls')">返回通话管理</el-button
    ><el-button v-if="profile.can_export" :disabled="busy" @click="exportRows"
      >导出当前结果</el-button
    ></PageHeader
  >
  <el-tabs
    :model-value="exportMode ? 'exports' : route.query.pool || 'all'"
    @update:model-value="
      (v) =>
        v === 'exports'
          ? (exportMode = true)
          : ((exportMode = false),
            replaceQuery({ ...route.query, pool: v === 'all' ? undefined : v, page: 1 }))
    "
    ><el-tab-pane name="all" label="全部话单" /><el-tab-pane
      name="public"
      label="公海数据" /><el-tab-pane name="assigned" label="已分配到数据" /><el-tab-pane
      name="exports"
      label="数据导出"
  /></el-tabs>
  <el-card v-if="exportMode" header="自定义导出配置"
    ><el-form label-position="top" class="form-grid">
      <el-form-item label="查询类型"
        ><el-checkbox-group v-model="exportConfig.export_types" aria-label="导出查询类型"
          ><el-checkbox value="undialed">未拨打</el-checkbox
          ><el-checkbox value="unanswered">未接通</el-checkbox
          ><el-checkbox value="answered">已接通</el-checkbox></el-checkbox-group
        >
        ></el-form-item
      >
      <el-form-item label="查询字段"
        ><el-radio-group v-model="exportConfig.export_fields" aria-label="导出查询字段"
          ><el-radio value="calls">导出话单</el-radio
          ><el-radio value="phones">只导出号码</el-radio></el-radio-group
        ></el-form-item
      >
      <el-form-item label="导出对话内容范围"
        ><el-select v-model="exportConfig.export_dialogue" aria-label="导出对话内容范围"
          ><el-option label="主被叫双方完整对话" value="both" /><el-option
            label="仅被叫方对话"
            value="customer" /><el-option label="不导出对话" value="none" /></el-select
      ></el-form-item>
      <el-form-item label="导出方式"
        ><el-radio-group v-model="exportConfig.export_split" aria-label="导出方式"
          ><el-radio value="day">多个文件按日分</el-radio
          ><el-radio value="count">多个文件按号码数量分</el-radio></el-radio-group
        ></el-form-item
      >
      <el-form-item v-if="exportConfig.export_split === 'count'" label="每个文件号码数量"
        ><el-input-number
          v-model="exportConfig.export_count"
          :min="1"
          :max="10000"
          aria-label="每个文件号码数量"
      /></el-form-item>
      <el-form-item label="上传批量号码文件"
        ><input
          type="file"
          accept=".xlsx,.csv"
          aria-label="导出批量号码文件"
          @change="uploadNumbers" /><el-input
          v-model="exportConfig.export_phones"
          type="textarea"
          aria-label="导出批量号码"
          placeholder="逗号分隔，最多1000个号码"
      /></el-form-item>
    </el-form>
    <p>使用下方筛选条件，生成 ZIP 后在消息中心下载。</p>
    <el-button v-if="profile.can_export" type="primary" @click="exportRows"
      >自定义导出</el-button
    ></el-card
  >
  <el-card
    ><el-form label-position="top" class="form-grid ops-filters" @submit.prevent="apply"
      ><el-form-item label="号码"
        ><el-input v-model="filters.phone" aria-label="查询号码" clearable /></el-form-item
      ><el-form-item label="任务"
        ><el-select v-model="filters.campaign_id" clearable filterable aria-label="查询任务"
          ><el-option
            v-for="v in options.campaigns"
            :key="v.id"
            :value="v.id"
            :label="v.name" /></el-select></el-form-item
      ><el-form-item label="状态"
        ><el-select v-model="filters.status" clearable aria-label="查询通话状态"
          ><el-option
            v-for="s in [
              'created',
              'queued',
              'dialing',
              'in_ai',
              'waiting_human',
              'in_human',
              'completed',
              'failed',
              'no_answer',
              'busy',
              'voicemail',
            ]"
            :key="s"
            :value="s"
            :label="s" /></el-select></el-form-item
      ><el-form-item label="意向"
        ><el-input v-model="filters.intent" aria-label="查询意向" /></el-form-item
      ><el-form-item label="开始时间"
        ><el-input
          v-model="filters.from"
          type="datetime-local"
          aria-label="查询开始时间" /></el-form-item
      ><el-form-item label="结束时间"
        ><el-input
          v-model="filters.until"
          type="datetime-local"
          aria-label="查询结束时间" /></el-form-item
      ><el-form-item label="人工坐席"
        ><el-select v-model="filters.agent_id" clearable aria-label="查询坐席"
          ><el-option
            v-for="u in profile.users || []"
            :key="u.id"
            :value="u.id"
            :label="u.full_name" /></el-select></el-form-item
      ><el-form-item label="线路"
        ><el-select v-model="filters.line_id" clearable filterable aria-label="查询线路"
          ><el-option
            v-for="v in options.lines"
            :key="v.id"
            :value="v.id"
            :label="v.name" /></el-select></el-form-item
      ><el-form-item label="最短秒数"
        ><el-input
          v-model="filters.min_seconds"
          type="number"
          min="0"
          aria-label="最短秒数" /></el-form-item
      ><el-form-item label="最长秒数"
        ><el-input
          v-model="filters.max_seconds"
          type="number"
          min="0"
          aria-label="最长秒数" /></el-form-item
      ><el-form-item label="人工接管"
        ><el-select v-model="filters.handoff" clearable aria-label="查询接管"
          ><el-option label="是" value="true" /><el-option
            label="否"
            value="false" /></el-select></el-form-item
      ><el-form-item label="复核状态"
        ><el-select v-model="filters.review_state" clearable aria-label="查询复核状态"
          ><el-option label="自动" value="auto" /><el-option
            label="已复核"
            value="reviewed" /></el-select></el-form-item
      ><el-form-item label="坐席组"
        ><el-select v-model="filters.team_id" clearable filterable aria-label="查询坐席组"
          ><el-option
            v-for="v in options.teams"
            :key="v.id"
            :value="v.id"
            :label="v.name" /></el-select></el-form-item
      ><el-form-item label="标签"
        ><el-input v-model="filters.tag" aria-label="查询标签" /></el-form-item
      ><el-form-item label="负责人"
        ><el-select v-model="filters.owner_id" clearable aria-label="查询负责人"
          ><el-option
            v-for="u in profile.users || []"
            :key="u.id"
            :value="u.id"
            :label="u.full_name" /></el-select></el-form-item
      ><el-form-item
        v-for="[key, label] in [
          ['id', '话单ID'],
          ['node', '埋点名称'],
          ['grade', 'AI评级'],
          ['hangup_reason', '挂机原因'],
          ['remark', '备注'],
          ['gender', '性别'],
          ['batch_id', '批次号'],
          ['intent', '意向度'],
        ]"
        :key="key"
        :label="label"
        ><el-input v-model="filters[key]" :aria-label="'查询' + label" /></el-form-item
      ><el-form-item label="跟进状态"
        ><el-select v-model="filters.stage" clearable aria-label="查询跟进状态"
          ><el-option
            v-for="[v, l] in [
              ['open', '待跟进'],
              ['in_progress', '跟进中'],
              ['completed', '成功'],
              ['invalid', '放弃'],
            ]"
            :key="v"
            :value="v"
            :label="l" /></el-select></el-form-item
      ><el-form-item label="话术"
        ><el-input v-model="filters.script_id" aria-label="查询话术" /></el-form-item
      ><el-form-item label="质检账号"
        ><el-select v-model="filters.reviewer_id" clearable aria-label="查询质检账号"
          ><el-option
            v-for="u in profile.users || []"
            :key="u.id"
            :value="u.id"
            :label="u.full_name" /></el-select></el-form-item
      ><el-form-item
        v-for="[key, label] in [
          ['entered', '进线'],
          ['taken', '接管'],
          ['successful', '是否成功单'],
          ['lost', '是否呼损'],
          ['manual', '是否手拨'],
          ['answered', '是否接通'],
        ]"
        :key="key"
        :label="label"
        ><el-select v-model="filters[key]" clearable :aria-label="'查询' + label"
          ><el-option label="是" value="true" /><el-option
            label="否"
            value="false" /></el-select></el-form-item
    ></el-form>
    <div class="actions">
      <el-button type="primary" @click="apply">查询</el-button
      ><el-button @click="reset">重置</el-button
      ><el-button @click="router.push('/admin/tasks/inspection')">AI 巡检</el-button
      ><span>共 {{ total }} 条</span>
    </div></el-card
  >
  <el-card v-if="!exportMode"
    ><div class="toolbar">
      <el-select
        v-model="viewId"
        placeholder="选择常用视图"
        aria-label="话单常用视图"
        @change="chooseView"
        ><el-option
          v-for="v in views.filter((v) => v.kind === 'calls')"
          :key="v.id"
          :value="v.id"
          :label="v.name" /></el-select
      ><el-input v-model="viewName" placeholder="视图名称" aria-label="话单视图名称" /><el-button
        :disabled="!viewName"
        @click="saveView"
        >保存视图</el-button
      ><el-button
        v-if="viewId"
        @click="
          perform(
            () => ops('/views/' + viewId, 'DELETE'),
            async () => {
              views = await ops('/views');
              viewId = '';
            },
          )
        "
        >删除视图</el-button
      >
    </div>
    <el-checkbox-group v-model="visible"
      ><el-checkbox v-for="c in allColumns" :key="c.key" :value="c.key">{{
        c.label
      }}</el-checkbox></el-checkbox-group
    ><el-alert v-if="error" :title="error" type="error" :closable="false" /><DataGrid
      :rows="rows"
      :columns="columns"
      :loading="busy"
      ><template #actions="{ row }"
        ><el-button link @click="selected = row">查看通话证据</el-button
        ><el-button
          v-if="row.contact_id && !row.owner_id && row.status === 'completed'"
          link
          @click="convert(row)"
          >生成线索</el-button
        ><el-button
          v-if="row.contact_id"
          link
          @click="
            router.push({
              path: prefix() + '/operations/leads',
              query: { contact_id: row.contact_id },
            })
          "
          >查看线索</el-button
        ></template
      ></DataGrid
    ><el-pagination
      :current-page="page"
      :total="total"
      :page-size="20"
      layout="prev,pager,next,total"
      @current-change="replaceQuery({ ...route.query, page: $event })" /></el-card
  ><CallDetails
    v-if="selected"
    :call="selected"
    :role="profile.administrator ? 'admin' : 'agent'"
    @close="selected = null"
  />
</template>
