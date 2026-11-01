<script setup>
import { ref, computed } from 'vue';
import { request } from '../session';
import { useData, action } from '../data';
const segments = useData('/api/v1/marketing/segments'),
  versions = useData('/api/v1/marketing/dataset-versions'),
  jobs = useData('/api/v1/marketing/match-jobs'),
  snapshots = useData('/api/v1/marketing/snapshots'),
  tasks = useData('/api/v1/marketing/send-tasks', 5000),
  options = useData('/api/v1/marketing/send-options');
const codes = [
  ['CALL_ANSWERED', '外呼已接通'],
  ['CALL_NO_ANSWER', '外呼未接'],
  ['ANS_VOICE_ASSISTANT', '语音助手'],
  ['ANS_INSTANT_HANGUP', '秒挂'],
  ['INTENT_A', '意向 A'],
  ['INTENT_B', '意向 B'],
  ['SMS_DELIVERED', '短信已送达'],
  ['LINK_CLICKED', '短链已点击'],
  ['CONVERTED', '已转化'],
];
const taskStates = { running: '执行中', finished: '已完成', cancelled: '已取消' };

// segment editor
const editing = ref(false),
  segmentId = ref(null),
  model = ref({}),
  previewResult = ref(null),
  busy = ref(false);
function blank() {
  return {
    name: '',
    versionIds: [],
    matchJobId: '',
    hitOnly: false,
    scoreMin: null,
    scoreMax: null,
    grades: [],
    labels: [],
    includeBlocked: false,
    noCallDays: null,
    noSmsDays: null,
    linkNotClickedDays: null,
    excludeCodes: [],
    excludeCodesDays: null,
  };
}
function edit(row) {
  segmentId.value = row?.id || null;
  model.value = row ? { ...blank(), ...JSON.parse(row.rules), name: row.name } : blank();
  previewResult.value = null;
  editing.value = true;
}
const rules = computed(() => {
  const { name, ...r } = model.value;
  return Object.fromEntries(
    Object.entries(r).filter(([, v]) => v !== null && v !== '' && !(Array.isArray(v) && !v.length)),
  );
});
async function preview() {
  busy.value = true;
  try {
    previewResult.value = await action(
      () => request('/api/v1/marketing/segments/preview', 'POST', { rules: rules.value }),
      { success: '' },
    );
  } finally {
    busy.value = false;
  }
}
async function save() {
  const body = { name: model.value.name, rules: rules.value };
  await action(
    () =>
      segmentId.value
        ? request(`/api/v1/marketing/segments/${segmentId.value}`, 'PUT', body)
        : request('/api/v1/marketing/segments', 'POST', body),
    { refresh: segments.load },
  );
  editing.value = false;
}

// freeze
const freezing = ref(null),
  controlPercent = ref(10);
async function freeze() {
  await action(
    () =>
      request(`/api/v1/marketing/segments/${freezing.value.id}/snapshots`, 'POST', {
        controlPercent: controlPercent.value,
      }),
    { refresh: () => Promise.all([snapshots.load(), segments.load()]), success: '快照已生成' },
  );
  freezing.value = null;
}

// send task
const sending = ref(null),
  send = ref({});
const resources = (kind) =>
  (options.data.value?.sms_resources || []).filter((r) => r.kind === kind);
const flows = computed(() =>
  (options.data.value?.flow_versions || []).filter(
    (f) => f.script_template_id === send.value.scriptTemplateId,
  ),
);
function openSend(snapshot) {
  options.load();
  sending.value = snapshot;
  send.value = {
    channel: 'call',
    campaignMode: 'existing',
    campaignId: null,
    campaignName: snapshot.name,
    mode: 'ai_only',
    scriptTemplateId: null,
    flowId: null,
    applicationId: '',
    productId: '',
    templateId: '',
    senderId: '',
    variables: '{}',
    landingUrl: '',
  };
}
async function createTask() {
  const s = send.value,
    body = { snapshotId: sending.value.id, channel: s.channel };
  if (s.channel === 'call') {
    if (s.campaignMode === 'existing') body.campaignId = s.campaignId;
    else
      body.newCampaign = {
        name: s.campaignName,
        mode: s.mode,
        ...(s.scriptTemplateId ? { script_template_id: s.scriptTemplateId } : {}),
        ...(s.flowId ? { script_flow_version_id: s.flowId } : {}),
      };
  } else {
    let variables;
    try {
      variables = JSON.parse(s.variables || '{}');
    } catch {
      throw new Error('模板变量必须是 JSON，例如 {"name":"客户"}');
    }
    body.sms = {
      applicationId: s.applicationId,
      productId: s.productId,
      templateId: s.templateId,
      senderId: s.senderId,
      variables,
      landingUrl: s.landingUrl,
    };
  }
  await action(() => request('/api/v1/marketing/send-tasks', 'POST', body), {
    refresh: tasks.load,
    success: '任务已创建，正在发送',
  });
  sending.value = null;
}
async function runTask(row) {
  await action(() => request(`/api/v1/marketing/send-tasks/${row.id}/run`, 'POST'), {
    refresh: tasks.load,
  });
}
async function cancelTask(row) {
  await action(() => request(`/api/v1/marketing/send-tasks/${row.id}/cancel`, 'POST'), {
    confirm: '取消后未发送的成员不再发送，已发送的不受影响。确认取消？',
    refresh: tasks.load,
  });
}
const detail = ref(null);
async function openTask(row) {
  detail.value = await request(`/api/v1/marketing/send-tasks/${row.id}`);
}
const snapshotName = (id) => snapshots.data.value?.find((s) => s.id === id)?.name || id;
</script>
<template>
  <PageHeader
    title="人群圈选"
    description="按数据包、撞库分数/等级/标签和历史触达圈选人群，冻结为快照（可设对照组），再批量创建 AI 外呼或短信任务。"
    ><el-button type="primary" @click="edit(null)">新建人群</el-button></PageHeader
  >
  <el-card>
    <h3>人群</h3>
    <DataGrid
      :rows="segments.data.value || []"
      :loading="segments.loading.value"
      :columns="[
        { key: 'name', label: '人群' },
        { key: 'snapshot_count', label: '快照数' },
        { key: 'updated_at', label: '更新时间', date: true },
      ]"
      ><template #actions="{ row }"
        ><el-button size="small" @click="edit(row)">编辑规则</el-button
        ><el-button size="small" type="primary" @click="freezing = row"
          >生成快照</el-button
        ></template
      ></DataGrid
    >
  </el-card>
  <el-card style="margin-top: 16px">
    <h3>快照</h3>
    <DataGrid
      :rows="snapshots.data.value || []"
      :loading="snapshots.loading.value"
      :pagination="10"
      :columns="[
        { key: 'name', label: '快照' },
        { key: 'size', label: '总人数' },
        { key: 'test_size', label: '实验组' },
        { key: 'control_size', label: '对照组' },
        { key: 'control_percent', label: '对照比例 %' },
        { key: 'created_at', label: '冻结时间', date: true },
      ]"
      ><template #actions="{ row }"
        ><el-button size="small" type="primary" @click="openSend(row)"
          >创建外呼/短信任务</el-button
        ></template
      ></DataGrid
    >
  </el-card>
  <el-card style="margin-top: 16px">
    <h3>发送任务</h3>
    <DataGrid
      :rows="tasks.data.value || []"
      :loading="tasks.loading.value"
      :pagination="10"
      :columns="[
        { key: 'snapshot_id', label: '快照', render: snapshotName },
        { key: 'channel', label: '渠道', render: (v) => (v === 'call' ? 'AI 外呼' : '短信') },
        { key: 'state', label: '状态', render: (v) => taskStates[v] || v },
        { key: 'processed', label: '进度', render: (v, r) => `${v} / ${r.total}` },
        { key: 'accepted', label: '已下发' },
        { key: 'rejected', label: '准入拦截' },
        { key: 'held_out', label: '对照组留存' },
        { key: 'created_at', label: '创建时间', date: true },
      ]"
      ><template #actions="{ row }"
        ><el-button size="small" @click="openTask(row)">详情</el-button
        ><template v-if="row.state === 'running'"
          ><el-button size="small" @click="runTask(row)">继续发送</el-button
          ><el-button size="small" type="danger" @click="cancelTask(row)">取消</el-button></template
        ></template
      ></DataGrid
    >
  </el-card>

  <el-dialog v-model="editing" :title="segmentId ? '编辑人群规则' : '新建人群'" width="760px">
    <el-form label-position="top">
      <el-form-item label="人群名称"
        ><el-input v-model="model.name" maxlength="128"
      /></el-form-item>
      <el-divider content-position="left">数据来源（至少选一项）</el-divider>
      <el-form-item label="数据包版本"
        ><el-select v-model="model.versionIds" multiple filterable style="width: 100%"
          ><el-option
            v-for="v in versions.data.value || []"
            :key="v.id"
            :label="`${v.dataset_name} · v${v.version_no}（${v.valid_count} 个有效号码）`"
            :value="v.id" /></el-select
      ></el-form-item>
      <el-form-item label="撞库任务"
        ><el-select v-model="model.matchJobId" clearable filterable style="width: 100%"
          ><el-option
            v-for="j in (jobs.data.value || []).filter(
              (j) => j.state === 'finished' || j.state === 'paused',
            )"
            :key="j.id"
            :label="`${j.partner_name} · ${j.total} 个号码 · ${j.created_at}`"
            :value="j.id" /></el-select
      ></el-form-item>
      <el-checkbox v-model="model.includeBlocked">包含导入时准入受限的号码</el-checkbox>
      <el-divider content-position="left">撞库结果条件（需选择撞库任务）</el-divider>
      <div class="actions">
        <el-checkbox v-model="model.hitOnly">仅命中</el-checkbox>
        <span>分数</span><el-input-number v-model="model.scoreMin" :min="0" :max="100" /><span
          >至</span
        ><el-input-number v-model="model.scoreMax" :min="0" :max="100" />
      </div>
      <el-form-item label="等级"
        ><el-checkbox-group v-model="model.grades"
          ><el-checkbox v-for="g in ['A', 'B', 'C', 'D']" :key="g" :value="g">{{
            g
          }}</el-checkbox></el-checkbox-group
        ></el-form-item
      >
      <el-form-item label="标签（命中任一即可）"
        ><el-select
          v-model="model.labels"
          multiple
          filterable
          allow-create
          default-first-option
          style="width: 100%"
      /></el-form-item>
      <el-divider content-position="left">触达历史</el-divider>
      <div class="actions">
        <span>近</span><el-input-number v-model="model.noCallDays" :min="1" :max="365" /><span
          >天未外呼</span
        >
        <span>近</span><el-input-number v-model="model.noSmsDays" :min="1" :max="365" /><span
          >天未发短信</span
        >
      </div>
      <div class="actions" style="margin-top: 8px">
        <span>近</span
        ><el-input-number v-model="model.linkNotClickedDays" :min="1" :max="365" /><span
          >天收到短信但未点击短链</span
        >
      </div>
      <el-form-item label="排除出现过以下结果的号码" style="margin-top: 8px"
        ><el-select v-model="model.excludeCodes" multiple style="width: 100%"
          ><el-option v-for="[c, l] in codes" :key="c" :label="l" :value="c" /></el-select
        ><span>统计近</span
        ><el-input-number v-model="model.excludeCodesDays" :min="1" :max="365" /><span
          >天（不填为全部历史）</span
        ></el-form-item
      >
    </el-form>
    <el-alert
      v-if="previewResult"
      type="success"
      :closable="false"
      :title="`预计圈选 ${previewResult.count} 人`"
      :description="previewResult.sample.length ? '示例：' + previewResult.sample.join('、') : ''"
    />
    <template #footer
      ><el-button :loading="busy" @click="preview">预览人数</el-button
      ><el-button type="primary" @click="save">保存</el-button></template
    >
  </el-dialog>

  <el-dialog
    :model-value="freezing !== null"
    title="生成快照"
    width="480px"
    @close="freezing = null"
  >
    <p>按当前规则冻结一份人群名单。快照生成后不再变化，后续任务与效果分析都基于快照。</p>
    <el-form label-position="top"
      ><el-form-item label="对照组比例（%，不触达，用于 A/B 效果对比）"
        ><el-input-number v-model="controlPercent" :min="0" :max="50" /></el-form-item
    ></el-form>
    <template #footer><el-button type="primary" @click="freeze">生成</el-button></template>
  </el-dialog>

  <el-dialog
    :model-value="sending !== null"
    title="从快照创建任务"
    width="640px"
    @close="sending = null"
  >
    <template v-if="sending">
      <p>
        快照：{{ sending.name }}，实验组 {{ sending.test_size }} 人将逐个经过准入检查后下发，对照组
        {{ sending.control_size }} 人不触达。
      </p>
      <el-radio-group v-model="send.channel"
        ><el-radio-button value="call">AI 外呼</el-radio-button
        ><el-radio-button value="sms">短信</el-radio-button></el-radio-group
      >
      <el-form v-if="send.channel === 'call'" label-position="top" style="margin-top: 12px">
        <el-radio-group v-model="send.campaignMode"
          ><el-radio value="existing">加入已有外呼任务</el-radio
          ><el-radio value="new">新建外呼任务</el-radio></el-radio-group
        >
        <el-form-item v-if="send.campaignMode === 'existing'" label="外呼任务"
          ><el-select v-model="send.campaignId" filterable style="width: 100%"
            ><el-option
              v-for="c in options.data.value?.campaigns || []"
              :key="c.id"
              :label="`${c.name}（${c.status}）`"
              :value="c.id" /></el-select
        ></el-form-item>
        <template v-else>
          <el-form-item label="任务名称"><el-input v-model="send.campaignName" /></el-form-item>
          <el-form-item label="外呼模式"
            ><el-select v-model="send.mode"
              ><el-option label="纯 AI" value="ai_only" /><el-option
                label="AI 转人工"
                value="ai_handoff" /></el-select
          ></el-form-item>
          <el-form-item label="话术模板（选填）"
            ><el-select v-model="send.scriptTemplateId" clearable style="width: 100%"
              ><el-option
                v-for="t in options.data.value?.script_templates || []"
                :key="t.id"
                :label="t.name"
                :value="t.id" /></el-select
          ></el-form-item>
          <el-form-item label="已发布流程（选填）"
            ><el-select v-model="send.flowId" clearable style="width: 100%"
              ><el-option
                v-for="f in flows"
                :key="f.id"
                :label="`${f.name} v${f.version}`"
                :value="f.id" /></el-select
          ></el-form-item>
          <p class="muted">新建的任务为草稿，成员加入后请到“外呼任务”页面启动。</p>
        </template>
      </el-form>
      <el-form v-else label-position="top" style="margin-top: 12px">
        <el-form-item label="消息应用（选填，不选则以控制台身份发送）"
          ><el-select v-model="send.applicationId" clearable style="width: 100%"
            ><el-option
              v-for="a in options.data.value?.sms_applications || []"
              :key="a.id"
              :label="a.name"
              :value="a.id" /></el-select
        ></el-form-item>
        <el-form-item label="产品"
          ><el-select v-model="send.productId" style="width: 100%"
            ><el-option v-for="r in resources('product')" :key="r.id" :label="r.name" :value="r.id"
          /></el-select>
        </el-form-item>
        <el-form-item label="模板"
          ><el-select v-model="send.templateId" style="width: 100%"
            ><el-option
              v-for="r in resources('template')"
              :key="r.id"
              :label="r.name"
              :value="r.id" /></el-select
        ></el-form-item>
        <el-form-item label="发送主体"
          ><el-select v-model="send.senderId" style="width: 100%"
            ><el-option v-for="r in resources('sender')" :key="r.id" :label="r.name" :value="r.id"
          /></el-select>
        </el-form-item>
        <el-form-item label="模板变量（JSON）"
          ><el-input v-model="send.variables" placeholder='{"name":"客户"}'
        /></el-form-item>
        <el-form-item label="落地页地址（选填，填写后每人生成短链替换模板中的 {link}）"
          ><el-input
            v-model="send.landingUrl"
            :disabled="!options.data.value?.links_available"
            :placeholder="options.data.value?.links_available ? 'https://' : '短链模块未接入'"
        /></el-form-item>
      </el-form>
    </template>
    <template #footer
      ><el-button type="primary" @click="createTask">创建并开始</el-button></template
    >
  </el-dialog>

  <el-drawer :model-value="detail !== null" size="50%" title="任务详情" @close="detail = null">
    <template v-if="detail">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="渠道">{{
          detail.channel === 'call' ? 'AI 外呼' : '短信'
        }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ taskStates[detail.state] }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.campaign" label="外呼任务"
          >{{ detail.campaign.name }}（{{ detail.campaign.status }}）</el-descriptions-item
        >
        <el-descriptions-item label="进度"
          >{{ detail.processed }} / {{ detail.total }}</el-descriptions-item
        >
        <el-descriptions-item label="已下发">{{ detail.accepted }}</el-descriptions-item>
        <el-descriptions-item label="准入拦截">{{ detail.rejected }}</el-descriptions-item>
        <el-descriptions-item label="对照组留存">{{ detail.held_out }}</el-descriptions-item>
      </el-descriptions>
      <h4>拦截原因</h4>
      <DataGrid
        :rows="detail.reasons"
        :columns="[
          { key: 'reason', label: '原因' },
          { key: 'count', label: '人数' },
        ]"
      />
    </template>
  </el-drawer>
</template>
<style scoped>
.muted {
  color: var(--el-text-color-secondary);
}
</style>
