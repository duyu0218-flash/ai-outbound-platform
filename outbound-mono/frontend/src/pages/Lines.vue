<script setup>
import { ref } from 'vue';
import { request } from '../session';
import { useData } from '../data';
const query = useData('/api/v1/admin/lines'),
  open = ref(false),
  editing = ref(null),
  tab = ref('basic'),
  model = ref({}),
  busy = ref(false),
  error = ref('');
const basic = [
  { key: 'name', label: '线路名称', required: true },
  {
    key: 'provider',
    label: '接入方式',
    required: true,
    options: [
      { value: 'http', label: 'HTTP语音网关' },
      { value: 'mock', label: '模拟线路（仅测试）' },
    ],
  },
  { key: 'caller_id', label: '主叫显示号码' },
  {
    key: 'max_concurrency',
    label: '同时通话上限',
    type: 'number',
    min: 1,
    max: 10000,
    hint: '同一时刻最多占用的通话数，不是每秒发起呼叫数。须与供应商开通容量一致。',
  },
  {
    key: 'enabled',
    label: '启用线路',
    type: 'boolean',
    hint: '启用仅允许进入路由候选，不代表已完成线路联调。',
  },
];
const access = [
  {
    key: 'gateway_url',
    label: '网关服务地址',
    placeholder: 'https://your-voice-gateway.example.com',
    hint: '当前支持HTTP桥接协议；原生SIP注册须由网关实现。',
  },
  {
    key: 'credential_ref',
    label: '网关凭据配置引用',
    placeholder: 'PRIMARY_PBX',
    hint: '由技术管理员维护服务端凭据。此处填写已配置的引用名称，页面不回显密钥。',
  },
];
const advanced = [
  {
    key: 'priority',
    label: '路由优先级',
    type: 'number',
    min: 1,
    max: 10000,
    hint: '数值越小越优先。高优先级可用线路有容量时优先选用。',
  },
  {
    key: 'weight',
    label: '同级容量权重',
    type: 'number',
    min: 1,
    max: 100,
    hint: '同一优先级内，按占用数 ÷（并发上限 × 权重）选择较空闲线路。权重不保证固定流量比例。',
  },
];
function edit(row) {
  editing.value = row;
  tab.value = 'basic';
  error.value = '';
  model.value = row
    ? Object.fromEntries([...basic, ...access, ...advanced].map((f) => [f.key, row[f.key]]))
    : {
        name: '',
        provider: 'http',
        gateway_url: '',
        credential_ref: '',
        caller_id: '',
        max_concurrency: 10,
        priority: 100,
        weight: 1,
        enabled: false,
      };
  open.value = true;
}
async function save() {
  busy.value = true;
  error.value = '';
  try {
    if (!model.value.name?.trim()) throw Error('请填写线路名称');
    if (model.value.provider === 'http' && !model.value.gateway_url?.trim())
      throw Error('HTTP语音网关须填写网关服务地址');
    await request(
      '/api/v1/admin/lines' + (editing.value ? '/' + editing.value.id : ''),
      editing.value ? 'PUT' : 'POST',
      model.value,
    );
    open.value = false;
    await query.load();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <PageHeader title="线路与号码" description="由技术管理员接入语音网关，管理主叫号码、容量与路由"
    ><el-button type="primary" @click="edit()">新增线路</el-button></PageHeader
  ><el-alert v-if="query.error.value" :title="query.error.value" type="error" /><el-card
    ><el-table :data="query.data.value || []" v-loading="query.loading.value"
      ><el-table-column prop="name" label="线路名称" /><el-table-column label="接入方式"
        ><template #default="{ row }">{{
          row.provider === 'mock'
            ? '模拟线路'
            : row.provider === 'http'
              ? 'HTTP语音网关'
              : row.provider + '网关'
        }}</template></el-table-column
      ><el-table-column prop="caller_id" label="主叫号码" /><el-table-column
        prop="max_concurrency"
        label="同时通话上限"
      /><el-table-column label="状态"
        ><template #default="{ row }"
          ><el-tag :type="row.enabled ? 'success' : 'info'">{{
            row.enabled ? '已启用' : '已停用'
          }}</el-tag></template
        ></el-table-column
      ><el-table-column label="操作"
        ><template #default="{ row }"
          ><el-button link @click="edit(row)">配置线路</el-button></template
        ></el-table-column
      ></el-table
    ><el-empty
      v-if="!query.loading.value && !query.data.value?.length"
      description="添加已采购的语音网关线路，填写主叫和开通容量" /></el-card
  ><el-dialog v-model="open" title="线路配置" width="min(820px,95vw)" :close-on-click-modal="false"
    ><el-alert v-if="error" :title="error" type="error" /><el-tabs v-model="tab"
      ><el-tab-pane label="基本信息" name="basic" /><el-tab-pane
        label="网关接入"
        name="access" /><el-tab-pane label="高级路由" name="advanced" /></el-tabs
    ><FieldForm
      :model="model"
      :fields="tab === 'basic' ? basic : tab === 'access' ? access : advanced"
      hide-submit
    /><el-alert
      v-if="tab === 'access'"
      title="配置保存不等于接入成功。正式使用前需完成网关连通、号码展示、回调、录音与容量验证。"
      :closable="false"
    /><template #footer
      ><el-button @click="open = false">取消</el-button
      ><el-button type="primary" :loading="busy" @click="save">保存线路配置</el-button></template
    ></el-dialog
  >
</template>
