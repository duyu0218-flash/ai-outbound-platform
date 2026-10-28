<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { request } from '../session';
const route = useRoute(),
  router = useRouter();
const sections = [
  ['overview', '消息概览'],
  ['content', '内容与主体'],
  ['campaigns', '人群与活动'],
  ['send', '发送消息'],
  ['records', '发送记录'],
  ['interactions', '互动与成效'],
  ['developers', '应用接入'],
  ['billing', '账户与账本'],
];
const section = computed(() =>
  sections.some(([k]) => k === route.query.section) ? route.query.section : 'overview',
);
const error = ref(''),
  success = ref(''),
  busy = ref(false),
  capability = ref({}),
  rows = ref([]),
  applications = ref([]),
  ledger = ref([]),
  balance = ref({}),
  detail = ref(null),
  events = ref([]),
  newKey = ref(null),
  batch = ref(null);
const resources = reactive({
  product: [],
  sender: [],
  template: [],
  audience: [],
  campaign: [],
  call_rule: [],
});
const model = reactive({
  kind: 'template',
  name: '',
  form: 'rcs',
  body: '',
  brand: '',
  unit_price: 1000,
  daily_recipient_limit: 3,
  simulation_outcome: 'DELIVERED',
  contact_ids: '',
  audience_id: '',
  product_id: '',
  template_id: '',
  sender_id: '',
  variables: '{}',
  budget: 1000000,
  expires_at: '',
  call_campaign_id: 0,
});
const message = reactive({
  product_id: '',
  sender_id: '',
  template_id: '',
  phone: '',
  contact_id: '',
  variables: '{}',
  expires_at: '',
  scheduled_at: '',
});
const app = reactive({ name: '', products: [], senders: [], templates: [] });
const report = ref({}),
  interactions = ref([]),
  callIntents = ref([]),
  webhookDeliveries = ref([]);
const interaction = reactive({ message_id: '', kind: 'reply', text: '', business_event_id: '' });
async function recordInteraction() {
  const payload = { kind: interaction.kind };
  if (interaction.kind === 'reply') payload.text = interaction.text;
  if (interaction.kind === 'conversion') payload.business_event_id = interaction.business_event_id;
  await api('/messages/' + interaction.message_id + '/interactions', 'POST', payload, {
    headers: { 'Idempotency-Key': key() },
  });
  success.value = '模拟互动已保存';
}
const creditAmount = ref(1000000),
  suppression = reactive({ contact_id: '', reason: '' });
const approved = (kind) => resources[kind].filter((r) => r.state === 'APPROVED');
const api = (path, method = 'GET', body, options = {}) =>
  request('/api/v1/admin/messaging' + path, method, body, options);
const key = () => crypto.randomUUID();
const date = (value) =>
  value ? new Date(value).toISOString() : new Date(Date.now() + 86400000).toISOString();
async function load() {
  const [caps, messages, apps, funds, entries, metrics, activity, intents, hooks, ...catalog] =
    await Promise.all([
      api('/capabilities'),
      api('/messages'),
      api('/applications'),
      api('/balance'),
      api('/ledger'),
      api('/report'),
      api('/interactions'),
      api('/call-intents'),
      api('/webhook-deliveries'),
      ...Object.keys(resources).map((kind) => api('/resources/' + kind)),
    ]);
  capability.value = caps;
  rows.value = messages;
  applications.value = apps;
  balance.value = funds;
  ledger.value = entries;
  report.value = metrics;
  interactions.value = activity;
  callIntents.value = intents;
  webhookDeliveries.value = hooks;
  Object.keys(resources).forEach((kind, i) => (resources[kind] = catalog[i]));
}
async function run(action) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
    await action();
    await load();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function saveResource() {
  const payload = {};
  if (model.kind === 'template') Object.assign(payload, { form: model.form, body: model.body });
  if (model.kind === 'sender') payload.brand = model.brand;
  if (model.kind === 'product')
    Object.assign(payload, {
      form: model.form,
      unit_price: model.unit_price,
      daily_recipient_limit: model.daily_recipient_limit,
      simulation_outcome: model.simulation_outcome,
    });
  if (model.kind === 'audience')
    payload.contact_ids = model.contact_ids
      .split(/[,\s]+/)
      .filter(Boolean)
      .map(Number);
  if (model.kind === 'call_rule')
    Object.assign(payload, {
      call_campaign_id: model.call_campaign_id,
      product_id: model.product_id,
      template_id: model.template_id,
      sender_id: model.sender_id,
      variables: JSON.parse(model.variables),
    });
  if (model.kind === 'campaign')
    Object.assign(payload, {
      audience_id: model.audience_id,
      product_id: model.product_id,
      template_id: model.template_id,
      sender_id: model.sender_id,
      variables: JSON.parse(model.variables),
      budget: model.budget,
      expires_at: date(model.expires_at),
    });
  const result = await api('/resources/' + model.kind, 'POST', { name: model.name, payload });
  success.value = `已保存草稿：${result.name}。批准后可供任务使用。`;
  model.name = '';
}
async function changeState(row, state) {
  await api('/resources/' + row.id + '/state', 'POST', { state });
  success.value =
    state === 'APPROVED' ? '内部批准已保存。尚未提交供应商审核。' : '已停用，新发送将被阻止。';
}
const pendingSendKey = ref(''),
  pendingSendBody = ref(null);
async function send() {
  const product = resources.product.find((p) => p.id === message.product_id);
  if (!product) throw new Error('请选择消息产品');
  const body = {
    service_product_id: message.product_id,
    sender_identity_id: message.sender_id,
    template_version_id: message.template_id,
    recipient: { type: 'phone', value: message.phone },
    consent_reference: message.contact_id,
    variables: JSON.parse(message.variables),
    purpose: 'marketing',
    channel_policy: { mode: 'fixed', allowed_forms: [product.payload.form], allow_fallback: false },
    expires_at: date(message.expires_at),
  };
  if (message.scheduled_at) body.scheduled_at = date(message.scheduled_at);
  if (!pendingSendKey.value) {
    pendingSendKey.value = key();
    pendingSendBody.value = body;
  }
  const result = await api('/messages', 'POST', pendingSendBody.value, {
    headers: { 'Idempotency-Key': pendingSendKey.value },
  });
  pendingSendKey.value = '';
  success.value = `已受理模拟消息 ${result.message_id}，当前状态 ${result.status}。`;
  detail.value = result;
}
watch(message, () => (pendingSendKey.value = ''));
watch(
  section,
  (value) => {
    if (value === 'campaigns') model.kind = 'audience';
    else if (value === 'content') model.kind = 'template';
    newKey.value = null;
  },
  { immediate: true },
);
async function open(row) {
  detail.value = await api('/messages/' + row.id);
  events.value = await api('/messages/' + row.id + '/events');
}
async function createApp() {
  newKey.value = await api('/applications', 'POST', {
    name: app.name,
    grants: { products: app.products, senders: app.senders, templates: app.templates },
  });
  success.value = '应用已创建，请保存本次展示的密钥。';
}
async function launch(row) {
  batch.value = await api(
    '/campaigns/' + row.id + '/launch',
    'POST',
    {},
    { headers: { 'Idempotency-Key': 'campaign:' + row.id } },
  );
  success.value = '活动已受理，可刷新查看批次结果。';
}
async function refreshBatch() {
  if (batch.value) batch.value = await api('/batches/' + batch.value.batch_id);
}
onMounted(() => run(async () => {}));
</script>
<template>
  <PageHeader title="消息营销" description="独立管理内容、人群、活动与下游应用"
    ><el-button :loading="busy" @click="run(async () => {})">刷新</el-button></PageHeader
  >
  <el-alert
    title="当前为模拟环境：尚未选择供应商，不会向手机发送消息，也不会产生真实费用。"
    type="warning"
    :closable="false"
  />
  <el-alert
    v-if="!capability.enabled"
    title="消息模块未启用，请联系平台管理员完成服务配置。"
    type="info"
    :closable="false"
  />
  <el-alert v-if="error" :title="error" type="error" show-icon />
  <el-alert v-if="success" :title="success" type="success" show-icon />
  <el-tabs :model-value="section" @tab-change="(name) => router.push({ query: { section: name } })">
    <el-tab-pane v-for="[name, label] in sections" :key="name" :name="name" :label="label" />
  </el-tabs>
  <el-card v-if="section === 'overview'">
    <h2>消息业务工作台</h2>
    <p>
      先建立产品、发送主体与模板，批准后可直接发送，也可组织人群活动。下游应用只能使用明确授权的资源。
    </p>
    <p>
      5G 消息（RCS）、阅信、手机厂商 IP
      消息和普通短信采用独立形态。真实终端覆盖、卡片展示、互动与计费能力待供应商接入验证。
    </p>
    <p>
      当前最近消息：{{ rows.length }} 条；模板：{{ resources.template.length }} 个；应用：{{
        applications.length
      }}
      个。
    </p>
    <el-button type="primary" @click="router.push({ query: { section: 'content' } })"
      >开始配置内容</el-button
    >
    <el-button @click="router.push({ query: { section: 'send' } })">发送一条模拟消息</el-button>
  </el-card>
  <template v-if="section === 'content' || section === 'campaigns'">
    <el-card
      ><h2>新建{{ section === 'content' ? '内容与服务资源' : '人群与活动' }}</h2>
      <el-form label-width="130px" @submit.prevent="run(saveResource)">
        <el-form-item label="资源类型"
          ><el-select v-model="model.kind" aria-label="资源类型"
            ><el-option
              v-for="[kind, label] in section === 'content'
                ? [
                    ['template', '消息模板'],
                    ['sender', '发送主体'],
                    ['product', '消息产品'],
                    ['call_rule', '外呼联动规则'],
                  ]
                : [
                    ['audience', '客户人群'],
                    ['campaign', '营销活动'],
                  ]"
              :key="kind"
              :value="kind"
              :label="label" /></el-select
        ></el-form-item>
        <el-form-item label="名称"
          ><el-input v-model="model.name" aria-label="资源名称" maxlength="128"
        /></el-form-item>
        <el-form-item v-if="['product', 'template'].includes(model.kind)" label="消息形态"
          ><el-select v-model="model.form" aria-label="消息形态"
            ><el-option label="5G 消息（RCS）" value="rcs" /><el-option
              label="阅信"
              value="yuexin" /><el-option label="厂商 IP 消息" value="oem_ip" /><el-option
              label="普通短信"
              value="sms" /></el-select
        ></el-form-item>
        <el-form-item v-if="model.kind === 'template'" label="正文"
          ><el-input
            v-model="model.body"
            aria-label="模板正文"
            type="textarea"
            :rows="4"
            maxlength="4000"
            placeholder="支持双花括号变量，如 name；批准后内容固定，修改请新建版本"
        /></el-form-item>
        <el-form-item v-if="model.kind === 'sender'" label="品牌主体"
          ><el-input v-model="model.brand" aria-label="品牌主体"
        /></el-form-item>
        <template v-if="model.kind === 'product'"
          ><el-form-item label="模拟单价（微元）"
            ><el-input-number
              v-model="model.unit_price"
              :min="0"
              :max="1000000000"
              :precision="0" /></el-form-item
          ><el-form-item label="单人每日上限"
            ><el-input-number
              v-model="model.daily_recipient_limit"
              :min="1"
              :max="100"
              :precision="0" /></el-form-item
          ><el-form-item label="模拟结果"
            ><el-select v-model="model.simulation_outcome"
              ><el-option label="送达" value="DELIVERED" /><el-option
                label="明确拒绝"
                value="REJECTED" /><el-option
                label="结果未知"
                value="UNKNOWN" /></el-select></el-form-item
        ></template>
        <el-form-item v-if="model.kind === 'audience'" label="联系人编号"
          ><el-input
            v-model="model.contact_ids"
            aria-label="人群联系人编号"
            type="textarea"
            placeholder="从客户管理获取编号，用逗号分隔；活动阶段最多 100 人"
        /></el-form-item>
        <template v-if="['campaign', 'call_rule'].includes(model.kind)">
          <el-form-item v-if="model.kind === 'call_rule'" label="外呼任务编号"
            ><el-input-number v-model="model.call_campaign_id" :min="0" :precision="0" /><span
              >0 表示无外呼任务归属的通话；启用联动迁移后，通话正常结束才创建消息。</span
            ></el-form-item
          >
          <el-form-item
            v-for="[field, kind, label] in [
              ['audience_id', 'audience', '客户人群'],
              ['product_id', 'product', '消息产品'],
              ['sender_id', 'sender', '发送主体'],
              ['template_id', 'template', '消息模板'],
            ]"
            :key="field"
            :label="label"
            v-show="model.kind === 'campaign' || kind !== 'audience'"
            ><el-select v-model="model[field]" :aria-label="'活动' + label"
              ><el-option
                v-for="row in approved(kind)"
                :key="row.id"
                :label="row.name"
                :value="row.id" /></el-select
          ></el-form-item>
          <el-form-item v-if="model.kind === 'campaign'" label="模拟预算（微元）"
            ><el-input-number v-model="model.budget" :min="1" :max="1000000000000" :precision="0"
          /></el-form-item>
          <el-form-item label="模板变量"
            ><el-input
              v-model="model.variables"
              aria-label="活动模板变量"
              type="textarea"
              placeholder='{"name":"客户"}'
          /></el-form-item>
          <el-form-item v-if="model.kind === 'campaign'" label="失效时间"
            ><input v-model="model.expires_at" type="datetime-local" aria-label="活动失效时间"
          /></el-form-item>
        </template>
        <el-button type="primary" native-type="submit" :disabled="busy || !capability.enabled"
          >保存草稿</el-button
        >
      </el-form>
    </el-card>
    <el-card
      v-for="kind in section === 'content'
        ? ['product', 'sender', 'template', 'call_rule']
        : ['audience', 'campaign']"
      :key="kind"
    >
      <h3>
        {{
          {
            product: '消息产品',
            sender: '发送主体',
            template: '消息模板',
            audience: '客户人群',
            campaign: '营销活动',
            call_rule: '外呼联动规则',
          }[kind]
        }}
      </h3>
      <DataGrid
        :rows="resources[kind]"
        :columns="[
          { key: 'name', label: '名称' },
          { key: 'id', label: '资源编号' },
          { key: 'state', label: '状态' },
        ]"
        :pagination="10"
        ><template #actions="{ row }"
          ><el-button
            v-if="row.state === 'DRAFT'"
            link
            @click="run(() => changeState(row, 'APPROVED'))"
            >批准</el-button
          ><el-button
            v-if="row.state === 'APPROVED'"
            link
            @click="run(() => changeState(row, 'DISABLED'))"
            >停用</el-button
          ><el-button
            v-if="kind === 'campaign' && row.state === 'APPROVED'"
            link
            @click="run(() => launch(row))"
            >启动模拟活动</el-button
          ><el-popover width="420" trigger="click">
            <pre>{{ JSON.stringify(row.payload, null, 2) }}</pre>
            <template #reference><el-button link>查看内容</el-button></template></el-popover
          ></template
        ></DataGrid
      >
    </el-card>
    <el-card v-if="batch"
      ><h3>活动批次 {{ batch.batch_id }}</h3>
      <p>状态：{{ batch.state }}</p>
      <el-button @click="run(refreshBatch)">刷新批次</el-button
      ><DataGrid
        :rows="batch.results"
        :columns="[
          { key: 'client_item_id', label: '联系人' },
          { key: 'status', label: '受理状态' },
          { key: 'message_id', label: '消息编号' },
          { key: 'error', label: '拒绝原因' },
        ]"
    /></el-card>
  </template>
  <el-card v-if="section === 'send'"
    ><h2>发送模拟消息</h2>
    <el-form label-width="130px" @submit.prevent="run(send)">
      <el-form-item
        v-for="[field, kind, label] in [
          ['product_id', 'product', '消息产品'],
          ['sender_id', 'sender', '发送主体'],
          ['template_id', 'template', '消息模板'],
        ]"
        :key="field"
        :label="label"
        ><el-select v-model="message[field]" :aria-label="label"
          ><el-option
            v-for="row in approved(kind)"
            :key="row.id"
            :label="row.name"
            :value="row.id" /></el-select
      ></el-form-item>
      <el-form-item label="接收号码"
        ><el-input
          v-model="message.phone"
          aria-label="接收号码"
          placeholder="例如 +8613800000000" /></el-form-item
      ><el-form-item label="授权联系人编号"
        ><el-input
          v-model="message.contact_id"
          aria-label="授权联系人编号"
          placeholder="必须与客户管理中已同意触达的联系人匹配" /></el-form-item
      ><el-form-item label="模板变量"
        ><el-input
          v-model="message.variables"
          aria-label="发送模板变量"
          type="textarea" /></el-form-item
      ><el-form-item label="计划发送时间"
        ><input
          v-model="message.scheduled_at"
          type="datetime-local"
          aria-label="计划发送时间" /></el-form-item
      ><el-form-item label="失效时间"
        ><input
          v-model="message.expires_at"
          type="datetime-local"
          aria-label="消息失效时间" /></el-form-item
      ><el-button type="primary" native-type="submit" :disabled="busy || !capability.enabled"
        >提交模拟发送</el-button
      ></el-form
    >
  </el-card>
  <template v-if="section === 'records'"
    ><el-card
      ><DataGrid
        :rows="rows"
        :columns="[
          { key: 'message_id', label: '消息编号' },
          { key: 'recipient_mask', label: '接收人' },
          { key: 'requested_form', label: '形态' },
          { key: 'status', label: '状态' },
          { key: 'billing_state', label: '账务状态' },
          { key: 'sales_amount', label: '模拟费用（微元）' },
        ]"
        :pagination="20"
        ><template #actions="{ row }"
          ><el-button link @click="run(() => open(row))">详情</el-button
          ><el-button
            v-if="row.status === 'QUEUED'"
            link
            @click="
              run(async () => {
                await api('/messages/' + row.id + '/cancel', 'POST');
                success = '已取消并释放预占额度';
              })
            "
            >取消</el-button
          ><el-button
            v-if="row.billing_state === 'SETTLED'"
            link
            @click="
              run(async () => {
                await api('/messages/' + row.id + '/refund', 'POST', { reason: '控制台模拟退款' });
                success = '模拟退款已入账';
              })
            "
            >模拟退款</el-button
          ></template
        ></DataGrid
      ></el-card
    >
    <el-card v-if="detail"
      ><h3>消息详情</h3>
      <pre>{{ JSON.stringify(detail, null, 2) }}</pre>
      <DataGrid
        :rows="events"
        :columns="[
          { key: 'sequence', label: '序号' },
          { key: 'type', label: '事件' },
          { key: 'created_at', label: '发生时间', date: true },
        ]"
      /><el-button
        @click="
          detail = null;
          events = [];
        "
        >返回列表</el-button
      ></el-card
    >
    <el-card
      ><h3>消息退订</h3>
      <el-form
        inline
        @submit.prevent="
          run(async () => {
            await api('/suppressions', 'POST', suppression);
            success = '退订已保存，后续发送将被阻止';
          })
        "
        ><el-form-item label="联系人编号"
          ><el-input v-model="suppression.contact_id" aria-label="退订联系人编号" /></el-form-item
        ><el-form-item label="原因"
          ><el-input v-model="suppression.reason" aria-label="退订原因" /></el-form-item
        ><el-button native-type="submit">保存退订</el-button></el-form
      ></el-card
    >
  </template>
  <template v-if="section === 'developers'"
    ><el-card
      ><h2>创建下游应用</h2>
      <p>
        应用与当前企业绑定，按产品、模板和主体授权。密钥只在创建时展示一次；撤销后新请求与待发送任务都会被阻止。
      </p>
      <el-form label-width="120px" @submit.prevent="run(createApp)"
        ><el-form-item label="应用名称"
          ><el-input v-model="app.name" aria-label="应用名称" /></el-form-item
        ><el-form-item
          v-for="[field, kind, label] in [
            ['products', 'product', '授权产品'],
            ['senders', 'sender', '授权主体'],
            ['templates', 'template', '授权模板'],
          ]"
          :key="field"
          :label="label"
          ><el-select v-model="app[field]" multiple :aria-label="label"
            ><el-option
              v-for="row in approved(kind)"
              :key="row.id"
              :label="row.name"
              :value="row.id" /></el-select></el-form-item
        ><el-button native-type="submit" type="primary" :disabled="busy || !capability.enabled"
          >创建应用</el-button
        ></el-form
      ><el-alert v-if="newKey" title="请立即保存以下密钥，关闭后无法再次查看。" type="warning" />
      <pre v-if="newKey">{{ JSON.stringify(newKey, null, 2) }}</pre>
      <el-button v-if="newKey" @click="newKey = null">已保存，隐藏密钥</el-button></el-card
    ><el-card
      ><DataGrid
        :rows="applications"
        :columns="[
          { key: 'name', label: '应用' },
          { key: 'id', label: '应用编号' },
          { key: 'enabled', label: '启用' },
        ]"
        ><template #actions="{ row }"
          ><el-button
            v-if="row.enabled"
            link
            @click="run(() => api('/applications/' + row.id + '/revoke', 'POST'))"
            >撤销应用</el-button
          ></template
        ></DataGrid
      ></el-card
    ><el-card
      ><h3>回调投递记录</h3>
      <DataGrid
        :rows="webhookDeliveries"
        :columns="[
          { key: 'event_id', label: '事件编号' },
          { key: 'state', label: '状态' },
          { key: 'attempts', label: '投递次数' },
          { key: 'last_status', label: 'HTTP 状态' },
        ]"
      />
      <h3>接入说明</h3>
      <p>
        服务路径 /openapi/v1；HMAC-SHA256 请求签名，时间戳与随机数防重放，写入使用
        Idempotency-Key。受理成功不代表送达。请通过消息查询与事件拉取补偿结果。
      </p>
      <p>
        正式供应商、终端能力探测、跨企业委托尚未开放，回调订阅可通过开放 API
        创建，接口以随代码交付的实际契约为准。
      </p></el-card
    ></template
  >
  <template v-if="section === 'interactions'">
    <el-card
      ><h2>互动记录</h2>
      <p>
        这里记录模拟互动和下游应用上报事件。上报转化不代表供应商或交易系统已核验；业务事件编号在同一应用内去重。
      </p>
      <el-form label-width="130px" @submit.prevent="run(recordInteraction)">
        <el-form-item label="消息编号"
          ><el-select v-model="interaction.message_id" aria-label="互动消息"
            ><el-option
              v-for="row in rows"
              :key="row.id"
              :label="row.message_id"
              :value="row.id" /></el-select
        ></el-form-item>
        <el-form-item label="互动类型"
          ><el-select v-model="interaction.kind" aria-label="互动类型"
            ><el-option
              v-for="[value, label] in [
                ['reply', '客户回复'],
                ['callback_requested', '预约回拨'],
                ['human_takeover', '转人工'],
                ['unsubscribe', '退订'],
                ['conversion', '业务转化'],
              ]"
              :key="value"
              :value="value"
              :label="label" /></el-select
        ></el-form-item>
        <el-form-item v-if="interaction.kind === 'reply'" label="回复内容"
          ><el-input v-model="interaction.text" aria-label="回复内容" maxlength="2000"
        /></el-form-item>
        <el-form-item v-if="interaction.kind === 'conversion'" label="业务事件编号"
          ><el-input v-model="interaction.business_event_id" aria-label="业务事件编号"
        /></el-form-item>
        <el-button native-type="submit" :disabled="busy || !capability.enabled"
          >记录模拟互动</el-button
        > </el-form
      ><DataGrid
        :rows="interactions"
        :columns="[
          { key: 'message_id', label: '消息编号' },
          { key: 'kind', label: '互动类型' },
          { key: 'source', label: '来源' },
          { key: 'contact_id', label: '联系人' },
          { key: 'text', label: '回复内容' },
          { key: 'business_event_id', label: '业务事件编号' },
          { key: 'created_at', label: '时间', date: true },
        ]"
        :pagination="20"
    /></el-card>
    <el-card
      ><h3>模拟发送与账务汇总</h3>
      <DataGrid
        :rows="report.messages || []"
        :columns="[
          { key: 'requested_form', label: '形态' },
          { key: 'state', label: '状态' },
          { key: 'billing_state', label: '账务状态' },
          { key: 'messages', label: '消息数' },
          { key: 'sales_amount_micro_cny', label: '报价合计（微元）' },
        ]" />
      <p>报价合计按当前账务状态分组展示，退款和冻结不能计为净收入。</p>
      <DataGrid
        :rows="report.interactions || []"
        :columns="[
          { key: 'kind', label: '互动类型' },
          { key: 'source', label: '来源' },
          { key: 'events', label: '事件数' },
        ]"
    /></el-card>
    <el-card
      ><h3>外呼联动记录</h3>
      <DataGrid
        :rows="callIntents"
        :columns="[
          { key: 'call_id', label: '通话编号' },
          { key: 'attempt', label: '通话代次' },
          { key: 'state', label: '状态' },
          { key: 'message_id', label: '消息编号' },
          { key: 'reason', label: '说明' },
        ]"
    /></el-card>
  </template>
  <template v-if="section === 'billing'"
    ><el-card
      ><h2>模拟账户</h2>
      <p>金额单位为微元，1 元 = 1,000,000 微元。以下记录不代表真实充值、采购成本或收入。</p>
      <p>
        可用：{{ balance.account?.available ?? 0 }}；冻结：{{
          balance.account?.frozen ?? 0
        }}；已结算：{{ balance.account?.spent ?? 0 }}
      </p>
      <el-form
        inline
        @submit.prevent="
          run(async () => {
            await api(
              '/sandbox-credit',
              'POST',
              { amount: creditAmount },
              { headers: { 'Idempotency-Key': key() } },
            );
            success = '模拟额度已入账';
          })
        "
        ><el-form-item label="增加模拟额度"
          ><el-input-number
            v-model="creditAmount"
            :min="1"
            :max="1000000000000"
            :precision="0" /></el-form-item
        ><el-button native-type="submit" :disabled="busy || !capability.enabled"
          >添加模拟额度</el-button
        ></el-form
      ></el-card
    ><el-card
      ><DataGrid
        :rows="ledger"
        :columns="[
          { key: 'kind', label: '类型' },
          { key: 'available_delta', label: '可用变动' },
          { key: 'frozen_delta', label: '冻结变动' },
          { key: 'spent_delta', label: '结算变动' },
          { key: 'clearing_delta', label: '对手分录' },
          { key: 'reason', label: '原因' },
          { key: 'created_at', label: '时间', date: true },
        ]"
        :pagination="20" /></el-card
  ></template>
</template>
<style scoped>
.el-card {
  margin: 16px 0;
}
.el-alert {
  margin: 8px 0;
}
.el-select {
  min-width: 280px;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 400px;
  overflow: auto;
}
</style>
