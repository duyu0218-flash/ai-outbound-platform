<script setup>
import { ref, computed } from 'vue';
import { request } from '../session';
import { useData } from '../data';
import { ops, perform } from '../operations';
import { ElMessageBox } from 'element-plus';
const groups = useData('/api/v1/ops/lines/groups');
const lineView = ref('list'),
  profiles = useData('/api/v1/ops/benchmark/line-profiles'),
  routes = useData(() =>
    lineView.value === 'routes' ? '/api/v1/ops/benchmark/reports/routes' : '',
  ),
  profileOpen = ref(null),
  profileModel = ref({}),
  lineSearch = ref('');
const groupRows = computed(() => {
  const m = new Map();
  for (const r of groups.data.value || []) {
    if (!r.group_name) continue;
    const a = m.get(r.group_name) || [];
    a.push(r.line_id);
    m.set(r.group_name, a);
  }
  return [...m.entries()].map(([name, ids]) => ({ name, ids: ids.join('、') }));
});
function lineProfile(row) {
  return profiles.data.value?.find((p) => String(p.object_id) === String(row.id));
}
function editProfile(row) {
  profileOpen.value = row;
  profileModel.value = {
    physical_id: '',
    source: '',
    reference_price: null,
    unit_price: null,
    ...lineProfile(row)?.draft,
  };
}
async function saveProfile() {
  const r = profileOpen.value;
  const v = await perform(
    () =>
      ops('/benchmark/line-profiles/' + r.id, 'PUT', {
        revision: lineProfile(r)?.revision || 0,
        config: profileModel.value,
      }),
    profiles.load,
  );
  if (v) profileOpen.value = null;
}

function groupName(id) {
  return groups.data.value?.find((g) => g.line_id === id)?.group_name || '';
}
async function groupLine(row) {
  let value;
  try {
    ({ value } = await ElMessageBox.prompt('仅对后续呼叫生效，清空可移出分组', '设置线路组', {
      inputValue: groupName(row.id),
      inputValidator: (v) => !v || v.trim().length <= 100,
    }));
  } catch {
    return;
  }
  await perform(
    () => ops('/lines/' + row.id + '/group', 'PUT', { group_name: (value || '').trim() }),
    groups.load,
  );
}
const impact = ref(null);
async function showImpact(row) {
  try {
    impact.value = await request('/api/v1/workspace/task-resources/line/' + row.id + '/impact');
  } catch (e) {
    error.value = e.message;
  }
}
import ResourceCatalog from '../components/ResourceCatalog.vue';
const connections = useData('/api/v1/workspace/catalog/connection');
function selectConnection(id) {
  const row = connections.data.value?.find((c) => c.object_id === id);
  if (row) {
    model.value.credential_ref = row.draft.credential_ref;
    model.value.gateway_url = row.draft.gateway_url;
  }
}
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
  ><el-tabs v-model="lineView"
    ><el-tab-pane label="线路列表" name="list" /><el-tab-pane
      label="线路组"
      name="groups" /><el-tab-pane label="多路由话单查询" name="routes" /></el-tabs
  ><el-alert v-if="query.error.value" :title="query.error.value" type="error" /><el-card
    v-if="lineView === 'list'"
    ><el-input
      v-model="lineSearch"
      aria-label="线路搜索"
      placeholder="线路名称、线路ID、物理ID"
      clearable /><el-table
      :data="
        (query.data.value || []).filter((r) =>
          [r.name, r.id, lineProfile(r)?.draft.physical_id].join(' ').includes(lineSearch),
        )
      "
      v-loading="query.loading.value"
      ><el-table-column prop="id" label="ID" /><el-table-column label="物理ID"
        ><template #default="{ row }">{{
          lineProfile(row)?.draft.physical_id || '—'
        }}</template></el-table-column
      ><el-table-column prop="name" label="线路名称" /><el-table-column label="参考价格（元/分钟）"
        ><template #default="{ row }">{{
          lineProfile(row)?.draft.reference_price ?? '—'
        }}</template></el-table-column
      ><el-table-column label="单价（元/分钟）"
        ><template #default="{ row }">{{
          lineProfile(row)?.draft.unit_price ?? '—'
        }}</template></el-table-column
      ><el-table-column label="线路来源"
        ><template #default="{ row }">{{
          lineProfile(row)?.draft.source || '—'
        }}</template></el-table-column
      ><el-table-column label="接入方式"
        ><template #default="{ row }">{{
          row.provider === 'mock'
            ? '模拟线路'
            : row.provider === 'http'
              ? 'HTTP语音网关'
              : row.provider + '网关'
        }}</template></el-table-column
      ><el-table-column label="线路组"
        ><template #default="{ row }"
          ><el-button link @click="groupLine(row)">{{
            groupName(row.id) || '设置线路组'
          }}</el-button></template
        ></el-table-column
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
          ><el-button link @click="edit(row)">配置线路</el-button
          ><el-button link @click="showImpact(row)">使用影响</el-button
          ><el-button link @click="editProfile(row)">价格与物理ID</el-button></template
        ></el-table-column
      ></el-table
    ><el-empty
      v-if="!query.loading.value && !query.data.value?.length"
      description="添加已采购的语音网关线路，填写主叫和开通容量" /></el-card
  ><el-card v-if="lineView === 'groups'"
    ><el-table :data="groupRows"
      ><el-table-column prop="name" label="线路组" /><el-table-column
        prop="ids"
        label="线路ID" /></el-table></el-card
  ><el-card v-if="lineView === 'routes'"
    ><el-alert v-if="routes.error.value" :title="routes.error.value" type="error" /><el-button
      @click="routes.load"
      >刷新话单</el-button
    ><DataGrid
      :rows="routes.data.value || []"
      :pagination="20"
      :columns="
        [
          ['id', '通话ID'],
          ['phone', '电话'],
          ['line_id', '线路ID'],
          ['attempt', '拨打轮次'],
          ['route_state', '路由状态'],
          ['created_at', '时间'],
          ['last_error', '失败原因'],
        ].map(([key, label]) => ({ key, label }))
      " /></el-card
  ><el-dialog :model-value="!!profileOpen" title="线路价格与物理信息" @close="profileOpen = null"
    ><el-form label-position="top"
      ><el-form-item
        v-for="[key, label] in [
          ['physical_id', '物理ID'],
          ['source', '线路来源'],
          ['reference_price', '参考价格（元/分钟）'],
          ['unit_price', '单价（元/分钟）'],
        ]"
        :key="key"
        :label="label"
        ><el-input v-model="profileModel[key]" :aria-label="label" /></el-form-item
      ><el-button type="primary" @click="saveProfile">保存线路资料</el-button></el-form
    ></el-dialog
  ><el-dialog :model-value="!!impact" title="线路使用影响" @close="impact = null"
    ><template v-if="impact"
      ><p>{{ impact.effect }}</p>
      <p>当前活动通话 {{ impact.active_calls }} 路</p>
      <el-table :data="impact.tasks"
        ><el-table-column prop="name" label="绑定任务" /><el-table-column
          prop="status"
          label="状态" /></el-table></template></el-dialog
  ><ResourceCatalog kind="connection" @changed="connections.load" /><el-dialog
    v-model="open"
    title="线路配置"
    width="min(820px,95vw)"
    :close-on-click-modal="false"
    ><el-alert v-if="error" :title="error" type="error" /><el-tabs v-model="tab"
      ><el-tab-pane label="基本信息" name="basic" /><el-tab-pane
        label="网关接入"
        name="access" /><el-tab-pane label="高级路由" name="advanced" /></el-tabs
    ><el-form-item v-if="tab === 'access'" label="选择已配置连接"
      ><el-select clearable aria-label="选择已配置连接" @change="selectConnection"
        ><el-option
          v-for="c in connections.data.value || []"
          :key="c.object_id"
          :value="c.object_id"
          :label="c.name + (c.credential_configured ? '' : '（服务端凭据待配置）')"
          :disabled="!c.enabled" /></el-select></el-form-item
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
