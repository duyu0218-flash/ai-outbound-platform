<script setup>
import { reactive, ref, computed, onMounted } from 'vue';
import { request, download } from '../session';
import { messageForm, microAmount } from '../message-presentation';
const api = (path, method = 'GET', body, options = {}) =>
  request('/api/v1/admin/messaging' + path, method, body, options);
const rows = ref([]),
  assets = ref([]),
  resources = ref([]),
  bills = ref([]),
  bindings = ref([]),
  selected = ref(null),
  error = ref(''),
  success = ref(''),
  busy = ref(false),
  open = ref(false),
  bill = ref(null),
  tab = ref('channels');
const channel = reactive({
  name: '',
  protocol: 'http',
  environment: 'test',
  forms: ['sms'],
  endpoint: '',
  host: '',
  port: 7890,
  username: '',
  sp_code: '',
  service_id: '',
  api_key: '',
  callback_secret: '',
  password: '',
  tps: 10,
  daily_limit: 10000,
  query_supported: true,
  billing_basis: 'delivered',
  supports: ['delivery'],
  purchase_unit_price: 0,
});
const binding = reactive({
  resource_id: '',
  provider_ref: '',
  state: 'PENDING',
  evidence: '',
  expires_at: '',
});
const acceptance = ref(''),
  realDevice = ref(false),
  importRef = ref(''),
  importChannel = ref(''),
  importRows = ref(''),
  uploadExpiry = ref('');
const available = computed(() =>
  resources.value
    .filter((r) => r.state === 'APPROVED')
    .concat(assets.value.filter((a) => a.state === 'READY')),
);
const states = {
  DRAFT: '待验证',
  ACTIVE: '已启用',
  DISABLED: '已停用',
  UNVERIFIED: '未验证',
  LOCAL_VERIFIED: '连接已验证',
  PROVIDER_VERIFIED: '已登记实发验收',
  PENDING: '待审核',
  APPROVED: '审核通过',
  REJECTED: '审核拒绝',
  READY: '可用',
  MATCHED: '一致',
  DIFFERENCE: '金额差异',
  UNMATCHED: '未找到消息',
  SEGMENT_REVIEW: '成本/分片需核查',
};
async function load() {
  const [c, a, s, t, b] = await Promise.all([
    api('/channels'),
    api('/assets'),
    api('/resources/sender'),
    api('/resources/template'),
    api('/reconciliations'),
  ]);
  rows.value = c;
  assets.value = a;
  resources.value = [...s, ...t];
  bills.value = b;
}
async function run(fn) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
    await fn();
    await load();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function create() {
  const config = {
    tps: channel.tps,
    daily_limit: channel.daily_limit,
    billing_basis: channel.billing_basis,
    purchase_unit_price: channel.purchase_unit_price,
    supports: channel.protocol === 'cmpp' ? ['delivery', 'reply', 'unsubscribe'] : channel.supports,
    query_supported: channel.protocol === 'http' && channel.query_supported,
  };
  Object.assign(
    config,
    channel.protocol === 'http'
      ? { endpoint: channel.endpoint }
      : {
          host: channel.host,
          port: channel.port,
          username: channel.username,
          sp_code: channel.sp_code,
          service_id: channel.service_id,
        },
  );
  await api('/channels', 'POST', {
    name: channel.name,
    protocol: channel.protocol,
    environment: channel.environment,
    forms: channel.protocol === 'cmpp' ? ['sms'] : channel.forms,
    config,
    credentials:
      channel.protocol === 'http'
        ? { api_key: channel.api_key, callback_secret: channel.callback_secret }
        : { password: channel.password },
  });
  channel.api_key = '';
  channel.callback_secret = '';
  channel.password = '';
  open.value = false;
  success.value = '通道已保存，请完成连接验证与资源审核。';
}
async function inspect(row) {
  selected.value = row;
  bindings.value = await api('/channels/' + row.id + '/bindings');
  acceptance.value = '';
  realDevice.value = false;
}
async function command(row, action, body) {
  await api('/channels/' + row.id + '/' + action, 'POST', body);
  success.value = action === 'probe' ? '连接验证通过；尚不代表手机已收到消息。' : '通道已更新';
  if (selected.value?.id === row.id) await inspect(await api('/channels/' + row.id));
}
async function bind() {
  const body = { ...binding };
  if (body.expires_at) body.expires_at = new Date(body.expires_at).toISOString();
  else delete body.expires_at;
  bindings.value = await api('/channels/' + selected.value.id + '/bindings', 'POST', body);
  success.value = '资源渠道审核已保存';
}
async function upload(event) {
  const file = event.target.files?.[0];
  if (!file) return;
  await run(async () => {
    if (file.size > 5 * 1024 * 1024) throw new Error('文件最多 5 MiB');
    const query = new URLSearchParams({ name: file.name });
    if (uploadExpiry.value) query.set('expires_at', new Date(uploadExpiry.value).toISOString());
    await api('/assets?' + query, 'POST', undefined, {
      body: file,
      headers: { 'Content-Type': 'application/octet-stream' },
    });
    success.value = '素材已上传，可用于富媒体模板。';
  });
  event.target.value = '';
}
async function reconcile() {
  const items = importRows.value
    .split(/\r?\n/)
    .filter((v) => v.trim())
    .map((line) => {
      const [provider_id, amount] = line.split(',').map((v) => v.trim());
      if (!provider_id || !/^\d+$/.test(amount)) throw new Error('每行填写供应商消息编号,金额微元');
      return { provider_id, amount: Number(amount) };
    });
  bill.value = await api('/reconciliations', 'POST', {
    channel_id: importChannel.value,
    external_ref: importRef.value,
    items,
  });
  success.value = '对账完成；差异需核查，未自动修改客户账本。';
}
onMounted(() => run(async () => {}));
</script>
<template>
  <PageHeader
    title="短信通道与素材"
    description="在现有平台中接入 HTTP / CMPP，管理渠道审核与供应商账单"
    ><el-button @click="$router.push('/admin/system/messaging')">对外 API 应用</el-button
    ><el-button :loading="busy" @click="run(async () => {})">刷新</el-button></PageHeader
  >
  <el-alert
    title="连接测试仅验证协议；生产通道须另行登记供应商实发及终端回执验收。HTTP 通道按平台接入规范对接。"
    :closable="false"
  />
  <el-alert v-if="error" :title="error" type="error" /><el-alert
    v-if="success"
    :title="success"
    type="success"
  />
  <el-tabs v-model="tab"
    ><el-tab-pane label="通道管理" name="channels" /><el-tab-pane
      label="媒体素材"
      name="assets" /><el-tab-pane label="供应商对账" name="bills"
  /></el-tabs>
  <template v-if="tab === 'channels'">
    <el-button type="primary" @click="open = true">新增通道版本</el-button>
    <el-table :data="rows"
      ><el-table-column prop="name" label="通道名称" /><el-table-column
        prop="protocol"
        label="协议"
      /><el-table-column label="消息类型"
        ><template #default="{ row }">{{
          row.forms.map(messageForm).join('、')
        }}</template></el-table-column
      ><el-table-column label="环境"
        ><template #default="{ row }">{{
          row.environment === 'production' ? '生产' : '测试'
        }}</template></el-table-column
      ><el-table-column label="状态"
        ><template #default="{ row }"
          >{{ states[row.state] }} / {{ states[row.verification] }}</template
        ></el-table-column
      ><el-table-column label="操作" min-width="240"
        ><template #default="{ row }"
          ><el-button link @click="run(() => inspect(row))">审核与详情</el-button
          ><el-button link :disabled="busy" @click="run(() => command(row, 'probe'))"
            >连接测试</el-button
          ><el-button
            link
            :disabled="busy"
            @click="
              run(() =>
                command(row, 'state', { state: row.state === 'ACTIVE' ? 'DISABLED' : 'ACTIVE' }),
              )
            "
            >{{ row.state === 'ACTIVE' ? '停用' : '启用' }}</el-button
          ></template
        ></el-table-column
      ></el-table
    >
  </template>
  <template v-if="tab === 'assets'"
    ><p>PNG / JPEG / MP4，单个不超过 5 MiB。供应商素材编号在通道审核中登记。</p>
    <label>素材到期时间 <input v-model="uploadExpiry" type="datetime-local" /></label
    ><label class="upload"
      >上传素材
      <input
        type="file"
        accept="image/png,image/jpeg,video/mp4"
        :disabled="busy"
        @change="upload" /></label
    ><el-table :data="assets"
      ><el-table-column prop="name" label="素材名称" /><el-table-column
        prop="id"
        label="素材编号"
      /><el-table-column prop="mime" label="类型" /><el-table-column
        prop="size_bytes"
        label="字节数"
      /><el-table-column label="状态"
        ><template #default="{ row }">{{ states[row.state] }}</template></el-table-column
      ><el-table-column label="操作"
        ><template #default="{ row }"
          ><el-button
            link
            @click="
              run(() => download('/api/v1/admin/messaging/assets/' + row.id + '/content', row.name))
            "
            >下载</el-button
          ><el-button
            link
            :disabled="row.state !== 'READY'"
            @click="run(() => api('/assets/' + row.id + '/disable', 'POST'))"
            >停用</el-button
          ></template
        ></el-table-column
      ></el-table
    ></template
  >
  <template v-if="tab === 'bills'"
    ><el-form label-width="150px" @submit.prevent="run(reconcile)"
      ><el-form-item label="供应商通道"
        ><el-select v-model="importChannel" aria-label="对账通道"
          ><el-option
            v-for="row in rows"
            :key="row.id"
            :value="row.id"
            :label="row.name" /></el-select></el-form-item
      ><el-form-item label="供应商账单编号"><el-input v-model="importRef" /></el-form-item
      ><el-form-item label="账单记录"
        ><el-input
          v-model="importRows"
          type="textarea"
          :rows="5"
          placeholder="每行：供应商消息编号,金额微元；最多1000行" /></el-form-item
      ><el-button native-type="submit" :disabled="busy">核对账单</el-button></el-form
    ><el-table :data="bills"
      ><el-table-column prop="external_ref" label="账单编号" /><el-table-column
        prop="item_count"
        label="记录数"
      /><el-table-column label="合计"
        ><template #default="{ row }">{{
          microAmount(row.total_amount)
        }}</template></el-table-column
      ><el-table-column label="详情"
        ><template #default="{ row }"
          ><el-button
            link
            @click="run(async () => (bill = await api('/reconciliations/' + row.id)))"
            >查看差异</el-button
          ></template
        ></el-table-column
      ></el-table
    ><el-table v-if="bill" :data="bill.items"
      ><el-table-column prop="provider_id" label="供应商编号" /><el-table-column
        prop="billed_amount"
        label="账单微元"
      /><el-table-column prop="recorded_amount" label="已记录微元" /><el-table-column
        label="核对结果"
        ><template #default="{ row }">{{ states[row.state] }}</template></el-table-column
      ></el-table
    ></template
  >
  <el-dialog v-model="open" title="新增通道版本" width="min(760px,95vw)"
    ><el-form label-width="150px" @submit.prevent="run(create)"
      ><el-form-item label="名称"
        ><el-input v-model="channel.name" aria-label="通道名称" /></el-form-item
      ><el-form-item label="协议"
        ><el-select v-model="channel.protocol" aria-label="通道协议"
          ><el-option value="http" label="HTTP 接入规范 v1" /><el-option
            value="cmpp"
            label="CMPP 3.0" /></el-select></el-form-item
      ><el-form-item label="环境"
        ><el-select v-model="channel.environment"
          ><el-option value="test" label="测试" /><el-option
            value="production"
            label="生产" /></el-select
      ></el-form-item>
      <template v-if="channel.protocol === 'http'"
        ><el-form-item label="消息形态"
          ><el-checkbox-group v-model="channel.forms"
            ><el-checkbox
              v-for="form in ['sms', 'rcs', 'yuexin', 'oem_ip']"
              :key="form"
              :value="form"
              >{{ messageForm(form) }}</el-checkbox
            ></el-checkbox-group
          ></el-form-item
        ><el-form-item label="接口地址"
          ><el-input
            v-model="channel.endpoint"
            aria-label="HTTP接口地址"
            placeholder="https://供应商域名/接口路径" /></el-form-item
        ><el-form-item label="接口密钥"
          ><el-input
            v-model="channel.api_key"
            type="password"
            autocomplete="new-password" /></el-form-item
        ><el-form-item label="回执签名密钥"
          ><el-input
            v-model="channel.callback_secret"
            type="password"
            autocomplete="new-password"
            placeholder="至少32个字符" /></el-form-item
        ><el-form-item label="查询与回执"
          ><el-checkbox v-model="channel.query_supported">支持消息查询</el-checkbox
          ><el-checkbox-group v-model="channel.supports"
            ><el-checkbox
              v-for="[value, label] in [
                ['delivery', '送达'],
                ['display', '展示'],
                ['read', '已读'],
                ['click', '点击'],
                ['reply', '回复'],
                ['unsubscribe', '退订'],
              ]"
              :key="value"
              :value="value"
              >{{ label }}</el-checkbox
            ></el-checkbox-group
          ></el-form-item
        ></template
      >
      <template v-else
        ><el-form-item
          v-for="[field, label] in [
            ['host', '网关主机'],
            ['username', 'CMPP账号'],
            ['sp_code', '接入码'],
            ['service_id', '业务代码'],
          ]"
          :key="field"
          :label="label"
          ><el-input v-model="channel[field]" /></el-form-item
        ><el-form-item label="端口"
          ><el-input-number v-model="channel.port" :min="1" :max="65535" /></el-form-item
        ><el-form-item label="密码"
          ><el-input v-model="channel.password" type="password" autocomplete="new-password"
        /></el-form-item>
        <p>CMPP 主机须由平台运维加入地址白名单。此协议仅承载普通短信。</p></template
      >
      <el-form-item label="每秒消息上限"
        ><el-input-number v-model="channel.tps" :min="1" :max="1000" /></el-form-item
      ><el-form-item label="每日计费单位上限"
        ><el-input-number v-model="channel.daily_limit" :min="1" :max="10000000" /></el-form-item
      ><el-form-item label="客户计费口径"
        ><el-select v-model="channel.billing_basis"
          ><el-option value="delivered" label="最终送达计费" /><el-option
            value="accepted"
            label="通道受理计费" /></el-select></el-form-item
      ><el-button native-type="submit" type="primary" :disabled="busy">保存通道</el-button></el-form
    ></el-dialog
  >
  <el-drawer
    :model-value="!!selected"
    title="通道审核"
    size="min(850px,95vw)"
    @close="selected = null"
    ><template v-if="selected"
      ><h3>{{ selected.name }}</h3>
      <p>通道编号：{{ selected.id }}</p>
      <p>回执地址：/api/v1/webhooks/messaging/{{ selected.id }}</p>
      <p>{{ states[selected.verification] }}。连接参数固定；调整配置时新增通道版本。</p>
      <el-table :data="bindings"
        ><el-table-column prop="resource_id" label="资源编号" /><el-table-column
          prop="provider_ref"
          label="供应商编号"
        /><el-table-column label="审核状态"
          ><template #default="{ row }">{{ states[row.state] }}</template></el-table-column
        ></el-table
      ><el-form label-width="130px" @submit.prevent="run(bind)"
        ><el-form-item label="模板、签名或素材"
          ><el-select v-model="binding.resource_id" filterable aria-label="渠道审核资源"
            ><el-option
              v-for="row in available"
              :key="row.id"
              :value="row.id"
              :label="row.name + ' / ' + (row.kind || '素材')" /></el-select></el-form-item
        ><el-form-item label="供应商资源编号"
          ><el-input v-model="binding.provider_ref" /></el-form-item
        ><el-form-item label="审核状态"
          ><el-select v-model="binding.state"
            ><el-option
              v-for="value in ['PENDING', 'APPROVED', 'REJECTED', 'DISABLED']"
              :key="value"
              :value="value"
              :label="states[value]" /></el-select></el-form-item
        ><el-form-item label="审核凭证 / 工单"><el-input v-model="binding.evidence" /></el-form-item
        ><el-form-item label="审核到期时间"
          ><input v-model="binding.expires_at" type="datetime-local" /></el-form-item
        ><el-button native-type="submit" :disabled="busy">保存渠道审核</el-button></el-form
      ><el-divider />
      <h3>生产实发验收登记</h3>
      <el-input
        v-model="acceptance"
        type="textarea"
        placeholder="填写终端、送达回执、退订测试和供应商验收工单，不填写密钥"
      /><el-checkbox v-model="realDevice">已完成真实终端实收及回执核对</el-checkbox
      ><el-button
        :disabled="busy || !realDevice"
        @click="
          run(() =>
            command(selected, 'acceptance', {
              evidence: acceptance,
              real_device_confirmed: realDevice,
            }),
          )
        "
        >登记验收</el-button
      ></template
    ></el-drawer
  >
</template>
<style scoped>
.el-alert,
.el-table,
.el-form {
  margin: 16px 0;
}
.el-select {
  min-width: 260px;
}
.upload {
  display: block;
  margin: 16px 0;
}
</style>
