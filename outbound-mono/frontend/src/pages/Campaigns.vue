<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { request } from '../session';
import { t } from '../i18n';
import { useData, action } from '../data';
import ContactImport from '../components/ContactImport.vue';
import ContactSelect from '../components/ContactSelect.vue';
const filter = ref('');
const route = useRoute(),
  page = ref(1),
  query = useData(
    () =>
      `/api/v1/workspace/tasks?page=${page.value}&size=50&keyword=${encodeURIComponent(filter.value)}`,
  ),
  scripts = useData('/api/v1/workspace/scripts?size=200'),
  batches = useData('/api/v1/workspace/contact-batches'),
  clients = useData('/api/v1/ops/clients?status=active&size=200'),
  system = useData('/api/v1/admin/system-overview');
const teams = useData('/api/v1/ops/teams');
const clone = (v) => JSON.parse(JSON.stringify(v));
const open = ref(false),
  model = ref({}),
  editing = ref(null),
  step = ref(0),
  importing = ref(false),
  busy = ref(false),
  error = ref(''),
  detail = ref(null);
const flows = useData(() =>
  open.value && model.value.script_template_id
    ? `/api/v1/script-templates/${model.value.script_template_id}/flows`
    : '',
);
const templates = computed(() =>
  (scripts.data.value?.rows || []).filter((v) => v.published && v.state !== 'disabled'),
);
const selectedScript = computed(() =>
  templates.value.find((v) => String(v.id) === String(model.value.script_template_id)),
);
const contactCount = computed(
  () =>
    new Set(model.value.contact_ids || []).size +
    (batches.data.value || [])
      .filter((v) => (model.value.batch_ids || []).includes(v.id))
      .reduce((n, v) => n + (v.result.selected_count || 0), 0),
);
const rows = computed(() => query.data.value?.rows || []);
const basic = computed(() => [
  { key: 'name', label: '任务名称', required: true },
  {
    key: 'mode',
    label: '呼叫模式',
    options: [
      { value: 'ai_only', label: 'AI独立外呼' },
      { value: 'ai_handoff', label: 'AI转人工' },
      { value: 'human_only', label: '人工外呼' },
      { value: 'ai_with_sms', label: 'AI外呼并短信跟进' },
    ],
    required: true,
  },
  {
    key: 'script_template_id',
    label: '已发布话术',
    required: true,
    options: templates.value.map((v) => ({
      value: v.id,
      label: `${v.draft.name} · v${v.published_version}`,
    })),
  },
  {
    key: 'script_flow_version_id',
    label: '已发布流程画布（可选）',
    options: (flows.data.value || [])
      .filter((v) => v.status === 'published')
      .map((v) => ({ value: v.id, label: `${v.name} · v${v.version}` })),
  },
  {
    key: 'client_id',
    label: '线索交付企业（可选）',
    options: (clients.data.value || []).map((v) => ({ value: v.id, label: v.name })),
    hint: '未绑定时线索保留在平台，质检通过后不会交付到企业门户。',
  },
]);
const strategy = computed(() => [
  {
    key: 'concurrency',
    label: '任务最大同时通话数',
    type: 'number',
    min: 1,
    max: system.data.value?.capacity?.effective_max_concurrent_calls || 100,
  },
  { key: 'retry_limit', label: '失败重试次数', type: 'number', min: 0, max: 10 },
  { key: 'retry_interval_sec', label: '重试间隔（秒）', type: 'number', min: 1 },
  { key: 'attempt_interval_sec', label: '同号码最小呼叫间隔（秒）', type: 'number', min: 1 },
  { key: 'recording_enabled', label: '通话录音', type: 'boolean' },
  {
    key: 'hangup_sms_enabled',
    label: '挂断后短信规则',
    type: 'boolean',
    hint: '仅启用后续规则，实际发送仍受短信通道、模板与平台开关控制。',
  },
]);
async function run(fn) {
  busy.value = true;
  error.value = '';
  try {
    await fn();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function edit(row) {
  await run(async () => {
    editing.value = row;
    step.value = 0;
    if (row) {
      const v = await request('/api/v1/workspace/tasks/' + row.id);
      model.value = {
        ...v,
        batch_ids: v.snapshot?.batch_ids || [],
        contact_ids: v.snapshot ? v.snapshot.direct_contact_ids || [] : v.contacts.map((c) => c.id),
        client_id: null,
        execution_policy: clone(v.snapshot?.policy || {}),
        start_at: v.snapshot?.start_at || '',
        end_at: v.snapshot?.end_at || '',
      };
      if (!v.snapshot && v.contact_count > 50)
        throw Error(
          '历史任务超过50个联系人，请在客户名单建立完整批次后复制新任务，避免覆盖原名单。',
        );
      const bindings = await request('/api/v1/ops/clients/bindings?campaign_ids=' + row.id);
      model.value.client_id = bindings[0]?.client_id || null;
    } else
      model.value = {
        name: '',
        mode: 'ai_only',
        script_template_id: null,
        script_flow_version_id: null,
        batch_ids: route.query.batch_id ? [route.query.batch_id] : [],
        contact_ids: [],
        concurrency: 5,
        retry_limit: 1,
        retry_interval_sec: 30,
        attempt_interval_sec: 1800,
        recording_enabled: true,
        hangup_sms_enabled: false,
        voice_ai_pipeline: 'inherit',
        client_id: null,
        execution_policy: {},
        start_at: '',
        end_at: '',
      };
    open.value = true;
  });
}
function next() {
  error.value = '';
  if (step.value === 0 && (!model.value.name?.trim() || !model.value.script_template_id)) {
    error.value = '请填写任务名称并选择已发布话术。';
    return;
  }
  if (step.value === 1 && !contactCount.value) {
    error.value = '请选择名单批次或联系人。';
    return;
  }
  if (step.value === 1 && !Object.keys(model.value.execution_policy || {}).length)
    model.value.execution_policy = clone(selectedScript.value?.published?.policy || {});
  step.value++;
}
async function save() {
  await run(async () => {
    const keys = [
      'name',
      'mode',
      'script_template_id',
      'script_flow_version_id',
      'concurrency',
      'retry_limit',
      'retry_interval_sec',
      'attempt_interval_sec',
      'recording_enabled',
      'hangup_sms_enabled',
      'voice_ai_pipeline',
      'batch_ids',
      'contact_ids',
      'client_id',
      'execution_policy',
      'start_at',
      'end_at',
    ];
    if (!Object.keys(model.value.execution_policy || {}).length)
      model.value.execution_policy = clone(selectedScript.value?.published?.policy || {});
    const data = Object.fromEntries(
      keys.map((k) => [
        k,
        model.value[k] ??
          (['script_template_id', 'script_flow_version_id'].includes(k) ? null : undefined),
      ]),
    );
    const v = await request(
      '/api/v1/workspace/tasks' + (editing.value ? '/' + editing.value.id : ''),
      editing.value ? 'PUT' : 'POST',
      data,
    );
    open.value = false;
    await query.load();
    detail.value = await request('/api/v1/workspace/tasks/' + v.id);
  });
}
async function imported(value) {
  await batches.load();
  model.value.batch_ids = [...new Set([...(model.value.batch_ids || []), value.batch_id])];
  importing.value = false;
}
function command(row, kind) {
  return action(
    () =>
      request(
        `/api/v1/campaigns/${row.id}/${kind === 'start' ? 'start?async_dial=true' : kind}`,
        'POST',
      ),
    {
      confirm: ['start', 'stop'].includes(kind)
        ? kind === 'start'
          ? '启动后将按当前线路和通道配置执行，可能产生真实话费及模型费用。确认启动该任务？'
          : '确认停止任务？'
        : undefined,
      refresh: query.load,
    },
  );
}
onMounted(() => {
  if (route.query.batch_id) edit();
});
</script>
<template>
  <PageHeader title="外呼任务" description="选择已发布话术与客户名单，设置拨打策略，核对后保存任务"
    ><el-button type="primary" @click="edit()">创建任务</el-button></PageHeader
  ><el-alert
    v-if="error || query.error.value"
    :title="error || query.error.value"
    type="error"
    :closable="false"
  /><el-card
    ><el-input
      v-model="filter"
      aria-label="搜索任务"
      @input="page = 1"
      placeholder="搜索任务名称"
      style="width: 300px"
      clearable /><el-table :data="rows" v-loading="query.loading.value"
      ><el-table-column prop="name" label="任务名称" /><el-table-column label="状态"
        ><template #default="{ row }">{{ t(row.status) }}</template></el-table-column
      ><el-table-column label="交付企业"
        ><template #default="{ row }">{{ row.client_name || '未绑定' }}</template></el-table-column
      ><el-table-column prop="contact_count" label="名单数量" /><el-table-column
        prop="concurrency"
        label="同时通话上限"
      /><el-table-column label="操作" min-width="280"
        ><template #default="{ row }"
          ><el-button
            link
            @click="run(async () => (detail = await request('/api/v1/workspace/tasks/' + row.id)))"
            >任务详情</el-button
          ><template v-if="['draft', 'failed', 'stopped', 'prepared'].includes(row.status)"
            ><el-button link type="primary" @click="command(row, 'start')">启动</el-button
            ><el-button link @click="edit(row)">编辑</el-button></template
          ><el-button v-if="row.status === 'running'" link @click="command(row, 'pause')"
            >暂停</el-button
          ><el-button v-if="row.status === 'paused'" link @click="command(row, 'resume')"
            >恢复</el-button
          ><el-button
            v-if="['running', 'paused', 'prepared'].includes(row.status)"
            link
            type="danger"
            @click="command(row, 'stop')"
            >停止</el-button
          ></template
        ></el-table-column
      ></el-table
    ><Pager :page="page" :count="rows.length" @change="page = $event"
  /></el-card>
  <el-dialog
    v-model="open"
    :title="editing ? '编辑任务' : '创建任务'"
    width="min(1000px,96vw)"
    :close-on-click-modal="false"
    ><el-steps :active="step" simple
      ><el-step title="基本信息" /><el-step title="选择名单" /><el-step title="拨打策略" /><el-step
        title="核对保存" /></el-steps
    ><el-alert v-if="error" :title="error" type="error" style="margin: 16px 0" />
    <FieldForm
      v-if="step === 0"
      :model="model"
      :fields="basic"
      hide-submit
      @change="
        (key) => {
          if (key === 'script_template_id') {
            model.script_flow_version_id = null;
            model.execution_policy = clone(selectedScript?.published?.policy || {});
          }
        }
      "
    />
    <template v-if="step === 1"
      ><div class="actions" style="margin: 20px 0">
        <el-button type="primary" @click="importing = true">上传号码 / 下载模板</el-button
        ><el-button @click="batches.load()">刷新批次</el-button>
      </div>
      <el-form label-position="top"
        ><el-form-item label="选择名单批次"
          ><el-select v-model="model.batch_ids" multiple aria-label="选择名单批次"
            ><el-option
              v-for="batch in batches.data.value || []"
              :key="batch.id"
              :value="batch.id"
              :label="`${batch.name} · ${batch.result.selected_count}个联系人`" /></el-select></el-form-item
        ><el-form-item label="补充选择联系人"
          ><ContactSelect v-model="model.contact_ids" /></el-form-item
      ></el-form>
      <p>
        预选 {{ contactCount }} 人，保存时按联系人去重。禁呼、授权与频控会在拨打前再次校验。
      </p></template
    >
    <template v-if="step === 2">
      <div class="form-grid">
        <el-form-item label="开始时间（本地时区，可选）"
          ><input
            type="datetime-local"
            aria-label="任务开始时间"
            :value="
              model.start_at
                ? new Date(
                    new Date(model.start_at) - new Date(model.start_at).getTimezoneOffset() * 60000,
                  )
                    .toISOString()
                    .slice(0, 16)
                : ''
            "
            @change="
              model.start_at = $event.target.value
                ? new Date($event.target.value).toISOString()
                : ''
            " /></el-form-item
        ><el-form-item label="结束时间（本地时区，可选）"
          ><input
            type="datetime-local"
            aria-label="任务结束时间"
            :value="
              model.end_at
                ? new Date(
                    new Date(model.end_at) - new Date(model.end_at).getTimezoneOffset() * 60000,
                  )
                    .toISOString()
                    .slice(0, 16)
                : ''
            "
            @change="
              model.end_at = $event.target.value ? new Date($event.target.value).toISOString() : ''
            "
        /></el-form-item>
      </div>
      <FieldForm
        :model="model.execution_policy"
        :fields="[
          { key: 'timezone', label: '任务服务时区' },
          { key: 'start_hour', label: '每日开始时刻', type: 'number', min: 0, max: 23 },
          { key: 'end_hour', label: '每日结束时刻', type: 'number', min: 1, max: 24 },
          {
            key: 'weekdays',
            label: '执行工作日',
            options: ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日'].map(
              (label, value) => ({ label, value }),
            ),
            multiple: true,
          },
          {
            key: 'handoff_team_ids',
            label: '接听团队',
            options: (teams.data.value || []).map((v) => ({ value: v.id, label: v.name })),
            multiple: true,
          },
          {
            key: 'handoff_fallback',
            label: '无人接听兜底',
            options: [
              { value: 'end', label: '礼貌结束' },
              { value: 'ai', label: '继续AI对话' },
            ],
          },
        ]"
        hide-submit
      />
      <FieldForm :model="model" :fields="strategy" hide-submit /><el-alert
        title="任务时间仍受平台合规限制。结束后不再发起新呼叫，在途通话继续；待拨记录可手动终止。"
        :closable="false"
      />
    </template>
    <el-descriptions v-if="step === 3" title="任务核对" :column="2" border
      ><el-descriptions-item label="任务名称">{{ model.name }}</el-descriptions-item
      ><el-descriptions-item label="话术发布版本"
        >{{ selectedScript?.draft.name }} · v{{
          selectedScript?.published_version
        }}</el-descriptions-item
      ><el-descriptions-item label="预选名单">{{ contactCount }}（保存时去重）</el-descriptions-item
      ><el-descriptions-item label="同时通话上限">{{ model.concurrency }}</el-descriptions-item
      ><el-descriptions-item label="执行状态">仅保存草稿，需在列表手动启动</el-descriptions-item
      ><el-descriptions-item label="交付企业">{{
        clients.data.value?.find((v) => v.id === model.client_id)?.name || '未绑定，线索保留平台'
      }}</el-descriptions-item></el-descriptions
    >
    <template #footer
      ><el-button v-if="step > 0" :disabled="busy" @click="step--">上一步</el-button
      ><el-button v-if="step < 3" type="primary" @click="next">下一步</el-button
      ><el-button v-else type="primary" :loading="busy" @click="save">保存任务</el-button></template
    ></el-dialog
  >
  <el-drawer :model-value="!!detail" title="任务详情" size="min(900px,95vw)" @close="detail = null"
    ><template v-if="detail"
      ><el-descriptions :column="2" border
        ><el-descriptions-item label="任务">{{ detail.name }}</el-descriptions-item
        ><el-descriptions-item label="状态">{{ t(detail.status) }}</el-descriptions-item
        ><el-descriptions-item label="名单数量">{{ detail.contact_count }}</el-descriptions-item
        ><el-descriptions-item label="话术版本">{{
          detail.snapshot ? 'v' + detail.snapshot.script_version : '历史任务（无新快照）'
        }}</el-descriptions-item></el-descriptions
      >
      <el-collapse v-if="detail.snapshot"
        ><el-collapse-item title="任务使用的话术与知识快照"
          ><p>
            话术发布 v{{ detail.snapshot.script_version }} · 保存于 {{ detail.snapshot.saved_at }} ·
            {{ detail.snapshot.knowledge?.length || 0 }} 条知识
          </p>
          <p style="white-space: pre-wrap">{{ detail.snapshot.script }}</p>
          <el-table :data="detail.snapshot.knowledge || []"
            ><el-table-column prop="title" label="知识" /><el-table-column
              prop="source"
              label="来源" /><el-table-column
              prop="version"
              label="知识版本" /></el-table></el-collapse-item
      ></el-collapse>
      <h3>拨打结果</h3>
      <el-table :data="detail.results"
        ><el-table-column label="状态"
          ><template #default="{ row }">{{
            t(String(row.status).toLowerCase())
          }}</template></el-table-column
        ><el-table-column prop="total" label="数量"
      /></el-table>
      <h3>名单预览（前50条）</h3>
      <el-table :data="detail.contacts"
        ><el-table-column prop="phone" label="号码" /><el-table-column
          prop="name"
          label="姓名" /><el-table-column prop="dnc" label="禁呼" /></el-table
      ><el-button @click="$router.push({ path: '/admin/calls', query: { campaign_id: detail.id } })"
        >查看通话记录</el-button
      ></template
    ></el-drawer
  ><ContactImport v-model="importing" select-for-task @imported="imported" />
</template>
