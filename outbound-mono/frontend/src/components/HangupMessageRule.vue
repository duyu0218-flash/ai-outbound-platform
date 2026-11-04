<script setup>
import { ref, onMounted } from 'vue';
import { request } from '../session';
import { useData, action } from '../data';
const props = defineProps({ taskId: [Number, String], draft: Object });
const emit = defineEmits(['defaults']);
const products = useData('/api/v1/admin/messaging/resources/product'),
  templates = useData('/api/v1/admin/messaging/resources/template'),
  senders = useData('/api/v1/admin/messaging/resources/sender');
const model = ref({
    product_id: '',
    template_id: '',
    sender_id: '',
    condition: 'completed',
    budget: 1000000,
  }),
  error = ref(''),
  busy = ref(false);
const simulated = ref(null),
  sampleCall = ref('');
async function simulate() {
  try {
    simulated.value = await request(
      '/api/v1/workspace/tasks/' + props.taskId + '/sms-rule/simulate',
      'POST',
      { call_id: sampleCall.value },
    );
  } catch (e) {
    error.value = e.message;
  }
}
const approved = (ref) =>
  ref.value?.filter(
    (r) => r.state === 'APPROVED' && (!r.payload.form || r.payload.form === 'sms'),
  ) || [];
async function load() {
  try {
    const v = props.taskId
      ? await request('/api/v1/workspace/tasks/' + props.taskId + '/sms-rule')
      : props.draft;
    if (v?.product_id) model.value = JSON.parse(JSON.stringify(v));
  } catch (e) {
    error.value = e.message;
  }
}
async function save() {
  if (!props.taskId) {
    emit('defaults', JSON.parse(JSON.stringify(model.value)));
    return;
  }
  busy.value = true;
  try {
    await action(
      async () => {
        model.value = await request(
          '/api/v1/workspace/tasks/' + props.taskId + '/sms-rule',
          'POST',
          { ...model.value, confirmed: true },
        );
      },
      {
        confirm:
          '确认启用此挂机短信规则？正常完成通话且符合触发条件时，按所选模板、签名和预算进入消息流水。未知发送结果不会自动重发。',
      },
    );
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <el-card :header="taskId ? '挂机短信联动' : '话术短信默认配置'"
    ><el-alert v-if="error" :title="error" type="error" />
    <p>
      任务保存后单独确认。仅未运行任务可调整；业务变量取任务冻结名单，客户授权、退订、频控与预算会在实际提交时再次校验。
    </p>
    <el-form label-position="top"
      ><el-form-item
        v-for="[key, label, data] in [
          ['product_id', '挂机短信产品', products.data],
          ['template_id', '挂机短信模板', templates.data],
          ['sender_id', '挂机短信签名', senders.data],
        ]"
        :key="key"
        :label="label"
        ><el-select v-model="model[key]" :aria-label="label"
          ><el-option
            v-for="r in approved(data)"
            :key="r.id"
            :value="r.id"
            :label="r.name" /></el-select></el-form-item
      ><el-form-item label="触发条件"
        ><el-select v-model="model.condition" aria-label="挂机短信触发条件"
          ><el-option value="completed" label="通话正常完成后" /><el-option
            value="qualified_lead"
            label="通话正常完成且业务规则判定为合格线索" /></el-select></el-form-item
      ><el-form-item label="该规则总预算（微元 CNY）"
        ><el-input-number
          v-model="model.budget"
          :min="1"
          :max="1000000000000" /></el-form-item></el-form
    ><label
      ><input
        type="checkbox"
        aria-label="另发通知短信"
        :checked="!!model.notify"
        @change="
          model.notify = $event.target.checked ? { contact_id: null, template_id: '' } : undefined
        "
      />另发通知短信</label
    ><template v-if="model.notify"
      ><p>通知发送给指定的已授权联系人，与客户挂机短信分别去重并共用本规则总预算。</p>
      <el-input-number
        v-model="model.notify.contact_id"
        aria-label="通知联系人编号"
        :min="1"
        :precision="0" /><el-select v-model="model.notify.template_id" aria-label="通知短信模板"
        ><el-option
          v-for="r in approved(templates.data)"
          :key="r.id"
          :value="r.id"
          :label="r.name" /></el-select
    ></template>
    <el-button type="primary" :loading="busy" @click="save">{{
      taskId ? '确认保存挂机短信规则' : '应用到话术草稿'
    }}</el-button>
    <p v-if="model.id">当前规则 {{ model.id }}</p>
    <details v-if="taskId && model.id">
      <summary>内部零价模拟核对</summary>
      <p>
        核对本任务已完成模拟通话的消息流水，接收人分别去重。此入口仅在关闭消息工作进程的内部测试环境可用。
      </p>
      <el-input v-model="sampleCall" aria-label="模拟通话ID" /><el-button @click="simulate"
        >核对零价模拟短信</el-button
      ><el-table v-if="simulated" :data="simulated"
        ><el-table-column prop="recipient_kind" label="接收类型" /><el-table-column
          prop="state"
          label="模拟状态" /><el-table-column
          prop="message_id"
          label="模拟消息编号" /><el-table-column prop="reason" label="处理说明"
      /></el-table>
    </details>
    <el-button link @click="$router.push('/admin/sms/records')"
      >查看短信提交与发送记录</el-button
    ></el-card
  >
</template>
