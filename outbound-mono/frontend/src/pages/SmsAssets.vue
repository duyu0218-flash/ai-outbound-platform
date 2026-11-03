<script setup>
import { ref, computed } from 'vue';
import { useData } from '../data';
import { request } from '../session';
const kind = ref('template'),
  keyword = ref(''),
  open = ref(false),
  model = ref({}),
  busy = ref(false),
  error = ref(''),
  detail = ref(null),
  query = useData(() => '/api/v1/admin/messaging/resources/' + kind.value),
  caps = useData('/api/v1/admin/messaging/capabilities');
const labels = { DRAFT: '草稿', APPROVED: '内部已批准', DISABLED: '已停用' };
const rows = computed(() =>
  (query.data.value || []).filter((v) => !keyword.value || v.name.includes(keyword.value)),
);
const variables = computed(() => [
  ...new Set(
    [...String(model.value.body || '').matchAll(/\{\{\s*([a-zA-Z][a-zA-Z0-9_]*)\s*\}\}/g)].map(
      (v) => v[1],
    ),
  ),
]);
const fields = computed(() => [
  { key: 'name', label: kind.value === 'template' ? '模板名称' : '签名名称', required: true },
  ...(kind.value === 'template'
    ? [
        {
          key: 'form',
          label: '消息类型',
          options: [
            { value: 'sms', label: '普通短信' },
            { value: 'rcs', label: '5G消息（RCS）' },
            { value: 'yuexin', label: '阅信' },
            { value: 'oem_ip', label: '厂商IP消息' },
          ],
        },
        {
          key: 'body',
          label: '模板正文',
          type: 'textarea',
          rows: 6,
          required: true,
          maxlength: 4000,
          placeholder: '您好，{{name}}，您的预约已登记。',
        },
      ]
    : [{ key: 'brand', label: '品牌 / 企业主体', required: true }]),
]);
function create(row) {
  model.value = row
    ? { name: row.name + '（新版本）', ...row.payload }
    : { name: '', form: 'sms', body: '', brand: '' };
  open.value = true;
  error.value = '';
}
async function run(fn) {
  busy.value = true;
  error.value = '';
  try {
    await fn();
    await query.load();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function save() {
  await run(async () => {
    await request('/api/v1/admin/messaging/resources/' + kind.value, 'POST', {
      name: model.value.name,
      payload:
        kind.value === 'template'
          ? { form: model.value.form, body: model.value.body }
          : { brand: model.value.brand },
    });
    open.value = false;
  });
}
async function state(row, value) {
  await run(() =>
    request('/api/v1/admin/messaging/resources/' + row.id + '/state', 'POST', { state: value }),
  );
}
</script>
<template>
  <PageHeader
    title="签名与模板"
    description="维护发送主体与消息内容，批准后的内容保持固定，修改时另建版本"
    ><el-button type="primary" :disabled="!caps.data.value?.enabled" @click="create()">{{
      kind === 'template' ? '新建模板' : '新建签名'
    }}</el-button></PageHeader
  ><el-alert
    title="当前为模拟通道。内部批准与供应商审核分别记录；真实通道审核尚未接入。"
    :closable="false"
  /><el-alert
    v-if="caps.error.value || caps.data.value?.enabled === false"
    :title="caps.error.value || '短信模块未启用，请联系系统管理员完成配置'"
    type="info"
  /><el-alert
    v-if="error || query.error.value"
    :title="error || query.error.value"
    type="error"
  /><el-tabs v-model="kind"
    ><el-tab-pane label="短信模板" name="template" /><el-tab-pane
      label="短信签名"
      name="sender" /></el-tabs
  ><el-card
    ><el-input
      v-model="keyword"
      aria-label="搜索短信内容"
      placeholder="按名称搜索"
      clearable
      style="max-width: 360px" /><el-table :data="rows" v-loading="query.loading.value"
      ><el-table-column
        prop="name"
        :label="kind === 'template' ? '模板名称' : '签名名称'"
      /><el-table-column label="状态"
        ><template #default="{ row }"
          ><el-tag>{{ labels[row.state] || row.state }}</el-tag></template
        ></el-table-column
      ><el-table-column label="消息类型 / 主体"
        ><template #default="{ row }">{{
          kind === 'sender'
            ? row.payload.brand
            : { sms: '普通短信', rcs: '5G消息', yuexin: '阅信', oem_ip: '厂商IP消息' }[
                row.payload.form
              ]
        }}</template></el-table-column
      ><el-table-column label="操作"
        ><template #default="{ row }"
          ><el-button link @click="detail = row">查看内容</el-button
          ><el-button
            v-if="row.state === 'DRAFT'"
            link
            type="primary"
            :disabled="busy"
            @click="state(row, 'APPROVED')"
            >内部批准</el-button
          ><el-button
            v-if="row.state === 'APPROVED'"
            link
            type="danger"
            :disabled="busy"
            @click="state(row, 'DISABLED')"
            >停用</el-button
          ><el-button link @click="create(row)">复制为新版本</el-button></template
        ></el-table-column
      ></el-table
    ><el-empty
      v-if="!query.loading.value && !rows.length"
      description="新建签名或模板后进行内部审核" /></el-card
  ><el-dialog
    v-model="open"
    :title="kind === 'template' ? '配置短信模板' : '配置短信签名'"
    width="min(800px,95vw)"
    ><FieldForm :model="model" :fields="fields" :busy="busy" @submit="save" /><template
      v-if="kind === 'template'"
      ><p>正文 {{ model.body?.length || 0 }} 字符；实际计费分段由供应商口径决定。</p>
      <p>模板变量：{{ variables.join('、') || '无' }}。发送时填写对应业务值。</p>
      <el-card header="内容预览"
        ><p style="white-space: pre-wrap">{{ model.body || '填写正文后预览' }}</p></el-card
      ></template
    ></el-dialog
  ><el-drawer :model-value="!!detail" title="消息内容" @close="detail = null"
    ><template v-if="detail"
      ><h3>{{ detail.name }}</h3>
      <p>{{ labels[detail.state] }}</p>
      <p style="white-space: pre-wrap">{{ detail.payload.body || detail.payload.brand }}</p>
      <small>资源编号：{{ detail.id }}</small></template
    ></el-drawer
  >
</template>
