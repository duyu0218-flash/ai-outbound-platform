<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import { request, session } from '../session';
import { useData, action } from '../data';
import { formatDate } from '../api';
import { messageState, microAmount } from '../message-presentation';
import ContactImport from '../components/ContactImport.vue';
import ContactSelect from '../components/ContactSelect.vue';
import AudiencePicker from '../components/AudiencePicker.vue';
const capabilities = useData('/api/v1/admin/messaging/capabilities');
const tasks = useData('/api/v1/workspace/sms-tasks'),
  products = useData('/api/v1/admin/messaging/resources/product'),
  templates = useData('/api/v1/admin/messaging/resources/template'),
  senders = useData('/api/v1/admin/messaging/resources/sender');
const memberPage = ref(1);
async function memberPageChanged() {
  detail.value.members = await request(
    '/api/v1/workspace/sms-tasks/' + detail.value.object_id + '/members?page=' + memberPage.value,
  );
}
const current = ref(null),
  model = ref({}),
  open = ref(false),
  step = ref(0),
  error = ref(''),
  busy = ref(false),
  importing = ref(false),
  check = ref(null),
  detail = ref(null);
const detailPlan = computed(() => detail.value?.published || detail.value?.draft || {});
const detailFields = computed(() => detailPlan.value.variable_schema?.fields || []);
watch(
  () => detail.value?.object_id,
  () => {
    memberPage.value = 1;
  },
);
const epoch = session.epoch;
let disposed = false,
  polling = false;
const timer = setInterval(async () => {
  if (disposed || polling || session.epoch !== epoch || detail.value?.job?.state !== 'queued')
    return;
  polling = true;
  const id = detail.value.object_id;
  try {
    const v = await request('/api/v1/workspace/sms-tasks/' + id);
    if (!disposed && session.epoch === epoch && detail.value?.object_id === id) detail.value = v;
  } catch (e) {
    if (!disposed) error.value = e.message;
  } finally {
    polling = false;
  }
}, 1500);
onUnmounted(() => {
  disposed = true;
  clearInterval(timer);
});
const approved = (data) =>
  data.value?.filter(
    (r) => r.state === 'APPROVED' && (!r.payload.form || r.payload.form === 'sms'),
  ) || [];
const states = {
  queued: '待提交',
  failed: '提交失败，可恢复',
  submitted: '全部已提交（非送达）',
  cancelled: '已停止后续提交',
};
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
    current.value = row ? await request('/api/v1/workspace/sms-tasks/' + row.object_id) : null;
    model.value = current.value
      ? JSON.parse(JSON.stringify(current.value.draft))
      : {
          name: '',
          template_id: '',
          sender_id: '',
          product_id: '',
          batch_ids: [],
          contact_ids: [],
          segment_snapshot_ids: [],
          excluded_contact_ids: [],
          budget: 1000000,
          expires_at: new Date(Date.now() + 86400000).toISOString(),
          scheduled_at: '',
        };
    step.value = 0;
    check.value = null;
    open.value = true;
  });
}
async function save() {
  await run(async () => {
    current.value = await request(
      '/api/v1/workspace/sms-tasks/' + (current.value?.object_id || crypto.randomUUID()),
      'PUT',
      { draft: model.value, expected_revision: current.value?.revision || 0 },
    );
    check.value = await request(
      '/api/v1/workspace/sms-tasks/' + current.value.object_id + '/preflight',
    );
    await tasks.load();
    step.value = 3;
  });
}
async function view(row) {
  await run(async () => {
    detail.value = await request('/api/v1/workspace/sms-tasks/' + row.object_id);
    check.value = await request('/api/v1/workspace/sms-tasks/' + row.object_id + '/preflight');
  });
}
async function start(row) {
  await action(
    async () => {
      const v = await request('/api/v1/workspace/sms-tasks/' + row.object_id);
      await request('/api/v1/workspace/sms-tasks/' + row.object_id + '/start', 'POST', {
        expected_revision: v.revision,
        request_key: crypto.randomUUID(),
        confirmed: true,
      });
      await tasks.load();
      open.value = false;
      await view(row);
    },
    {
      confirm:
        '确认冻结此任务的名单、签名、模板和预算并开始提交？当前供应商能力与费用以短信通道配置为准。',
    },
  );
}
async function command(row, kind) {
  await run(async () => {
    detail.value = await request(
      '/api/v1/workspace/sms-tasks/' + row.object_id + '/' + kind,
      'POST',
    );
    await tasks.load();
  });
}
async function imported(v) {
  model.value.batch_ids = [...new Set([...(model.value.batch_ids || []), v.batch_id])];
}
</script>
<template>
  <PageHeader
    title="短信发送任务"
    description="选择已审核模板与签名，导入号码及变量，核对预检后单独启动"
    ><el-button type="primary" @click="edit()">创建短信任务</el-button></PageHeader
  >
  <el-alert v-if="error || tasks.error.value" type="error" :title="error || tasks.error.value" />
  <el-alert
    v-if="capabilities.data.value && !capabilities.data.value.workers_enabled"
    title="当前环境未启用消息执行器。可保存、核对任务，启动后会排队，不会自动发送。"
    type="warning"
    :closable="false"
  />
  <el-alert
    title="短信按所选产品通道发送。已提交数量不等于送达量；送达回执在发送记录中查看。平台仍使用模拟账本，运营商实际费用需另行核对。"
    type="info"
    :closable="false"
  />
  <el-card
    ><el-table :data="tasks.data.value || []"
      ><el-table-column prop="name" label="任务名称" /><el-table-column label="人数"
        ><template #default="{ row }">{{ row.draft.recipient_count }}</template></el-table-column
      ><el-table-column label="签名 / 模板"
        ><template #default="{ row }"
          >{{ row.draft.sender_name }} / {{ row.draft.template_name }}</template
        ></el-table-column
      ><el-table-column label="状态"
        ><template #default="{ row }">{{
          row.published ? '已启动，查看提交进度' : '草稿'
        }}</template></el-table-column
      ><el-table-column label="操作"
        ><template #default="{ row }"
          ><el-button link @click="view(row)">任务详情</el-button
          ><template v-if="!row.published"
            ><el-button link @click="edit(row)">编辑</el-button
            ><el-button type="primary" link @click="start(row)">预检并启动</el-button></template
          ></template
        ></el-table-column
      ></el-table
    ><el-empty
      v-if="!tasks.data.value?.length"
      description="先配置审核通过的短信产品、签名与模板，再创建发送任务"
  /></el-card>
  <el-dialog
    v-model="open"
    title="短信任务配置"
    width="min(1050px,96vw)"
    :close-on-click-modal="false"
    ><el-alert v-if="error" type="error" :title="error" /><el-steps :active="step" simple
      ><el-step title="内容与签名" /><el-step title="接收名单" /><el-step
        title="预算与时间" /><el-step title="核对与保存"
    /></el-steps>
    <el-form v-if="step === 0" label-position="top"
      ><el-form-item label="任务名称"
        ><el-input v-model="model.name" aria-label="短信任务名称" /></el-form-item
      ><el-form-item
        v-for="[key, label, data] in [
          ['product_id', '短信产品', products.data],
          ['template_id', '短信模板', templates.data],
          ['sender_id', '短信签名', senders.data],
        ]"
        :key="key"
        :label="label"
        ><el-select v-model="model[key]" :aria-label="label"
          ><el-option
            v-for="item in approved(data)"
            :key="item.id"
            :label="item.name"
            :value="item.id" /></el-select
      ></el-form-item>
      <p>
        {{ templates.data.value?.find((t) => t.id === model.template_id)?.payload.body }}
      </p></el-form
    >
    <template v-if="step === 1"
      ><el-button @click="importing = true" :disabled="!model.template_id"
        >上传短信号码 / 下载变量模板</el-button
      ><AudiencePicker
        :key="model.batch_ids?.join(',')"
        :model="model"
        :template-id="model.template_id" /><el-form-item label="补充选择客户"
        ><ContactSelect v-model="model.contact_ids" /></el-form-item
    ></template>
    <el-form v-if="step === 2" label-position="top"
      ><el-form-item label="预算上限（微元 CNY，1元=1000000微元）"
        ><el-input-number
          v-model="model.budget"
          :min="1"
          :max="1000000000000"
          aria-label="短信预算" /></el-form-item
      ><el-form-item label="计划发送时间（UTC ISO，可选）"
        ><el-input v-model="model.scheduled_at" aria-label="短信计划时间" /></el-form-item
      ><el-form-item label="到期时间（UTC ISO）"
        ><el-input v-model="model.expires_at" aria-label="短信到期时间" /></el-form-item
    ></el-form>
    <template v-if="step === 3"
      ><p>
        任务：{{ model.name }}；签名：{{
          senders.data.value?.find((v) => v.id === model.sender_id)?.name
        }}；模板：{{ templates.data.value?.find((v) => v.id === model.template_id)?.name }}；预算
        {{ model.budget / 1000000 }} 元。
      </p>
      <el-alert v-if="current" type="success" title="草稿已保存，尚未开始发送" /><template
        v-if="check"
        ><p>
          冻结名单 {{ check.total }} 人，禁呼 {{ check.dnc }}，非已同意
          {{ check.not_consented }}，缺变量 {{ check.missing_variables }}；估算
          {{ check.estimated / 1000000 }} 元（模拟产品价）。
        </p>
        <p v-for="e in check.errors" :key="e">{{ e }}</p></template
      ></template
    >
    <template #footer
      ><el-button @click="open = false">关闭</el-button
      ><el-button v-if="step > 0" @click="step--">上一步</el-button
      ><el-button v-if="step < 3" @click="step++">下一步</el-button
      ><el-button type="primary" :loading="busy" @click="save">保存草稿并预检</el-button
      ><el-button v-if="current && check?.ready" @click="start(current)"
        >单独启动此任务</el-button
      ></template
    ></el-dialog
  >
  <ContactImport
    v-model="importing"
    channel="sms"
    :template-id="model.template_id"
    @imported="imported"
  />
  <el-drawer :model-value="!!detail" title="短信任务详情" size="70%" @close="detail = null"
    ><template v-if="detail"
      ><p>
        {{ detail.name }} · {{ states[detail.job?.state] || '草稿' }} · 已提交
        {{ detail.job?.submitted_count || 0 }} / {{ detail.draft.recipient_count }}
      </p>
      <p>
        停止提交只影响未提交成员；已进入消息流水的条目需在发送记录按其状态处理，不会自动重发未知结果。
      </p>
      <p v-if="detail.job?.error">{{ detail.job.error }}</p>
      <el-button v-if="detail.job?.state === 'failed'" @click="command(detail, 'retry')"
        >恢复未提交部分</el-button
      ><el-button
        v-if="['queued', 'failed'].includes(detail.job?.state)"
        @click="command(detail, 'cancel')"
        >停止后续提交</el-button
      ><el-button @click="view(detail)">刷新进度</el-button
      ><el-button @click="$router.push('/admin/sms/records')">查看发送记录与送达</el-button>
      <h3>任务内容与发送安排</h3>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="短信产品">{{
          detailPlan.product_name || '未选择'
        }}</el-descriptions-item>
        <el-descriptions-item label="短信签名">{{
          detailPlan.sender_name || '未选择'
        }}</el-descriptions-item>
        <el-descriptions-item label="短信模板">{{
          detailPlan.template_name || '未选择'
        }}</el-descriptions-item>
        <el-descriptions-item label="冻结版本">{{
          detail.published_version ? 'v' + detail.published_version : '草稿，尚未启动'
        }}</el-descriptions-item>
        <el-descriptions-item label="名单人数"
          >{{ detailPlan.recipient_count || 0 }} 人</el-descriptions-item
        >
        <el-descriptions-item label="模拟预算上限">{{
          microAmount(detailPlan.budget)
        }}</el-descriptions-item>
        <el-descriptions-item label="提交安排">{{
          detailPlan.scheduled_at ? formatDate(detailPlan.scheduled_at) : '确认启动后提交'
        }}</el-descriptions-item>
        <el-descriptions-item label="任务失效时间">{{
          formatDate(detailPlan.expires_at)
        }}</el-descriptions-item>
      </el-descriptions>
      <h3>冻结名单与业务变量</h3>
      <el-table :data="detail.members?.rows || []"
        ><el-table-column label="客户"
          ><template #default="{ row }"
            ><p>{{ row.variables.name || '未填写姓名' }}</p>
            <small>客户编号 {{ row.contact_id }}</small></template
          ></el-table-column
        >
        <el-table-column label="手机号码"
          ><template #default="{ row }">{{
            row.variables.phone || '未填写'
          }}</template></el-table-column
        >
        <el-table-column label="业务变量"
          ><template #default="{ row }"
            ><p v-for="field in detailFields" :key="field.key">
              {{ field.label || field.key }}：{{ row.variables[field.key] ?? '未填写' }}
            </p>
            <span v-if="!detailFields.length">无需额外变量</span></template
          ></el-table-column
        ></el-table
      ><el-pagination
        v-model:current-page="memberPage"
        :total="detail.members?.total || 0"
        :page-size="50"
        layout="prev,pager,next"
        @current-change="memberPageChanged"
      />
      <h3>提交批次</h3>
      <p>
        已建立 {{ detail.job?.chunks?.length || 0 }} 个批次；批次受理后可在发送记录查看逐条送达。
      </p>
      <el-collapse
        ><el-collapse-item
          v-for="(batch, index) in detail.job?.batch_results || []"
          :key="batch.id"
          :title="'第' + (index + 1) + '批 · ' + messageState(batch.state)"
        >
          <el-table :data="batch.results || []">
            <el-table-column prop="client_item_id" label="客户编号" />
            <el-table-column label="提交结果"
              ><template #default="{ row }">{{
                messageState(row.status)
              }}</template></el-table-column
            >
            <el-table-column prop="message_id" label="消息编号" show-overflow-tooltip />
            <el-table-column label="未受理原因"
              ><template #default="{ row }">{{
                row.error?.message || '—'
              }}</template></el-table-column
            >
          </el-table>
        </el-collapse-item></el-collapse
      >
      <p>
        详情最多展示前50个批次。每个条目的提交拒绝原因和消息ID保留在消息流水；提交作业恢复不重建已提交批次。
      </p></template
    ></el-drawer
  >
</template>
