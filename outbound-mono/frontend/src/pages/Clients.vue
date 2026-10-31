<script setup>
import { ref, computed } from 'vue';
import { request } from '../session';
import { useData, action } from '../data';
import ClientDetail from '../components/ClientDetail.vue';
const status = ref(''),
  keyword = ref(''),
  query = useData(
    () =>
      '/api/v1/ops/clients?' +
      new URLSearchParams({ status: status.value, keyword: keyword.value, size: '200' }),
  ),
  creating = ref(false),
  busy = ref(false),
  model = ref({}),
  selected = ref(null);
const fields = computed(() => [
  { key: 'name', label: '企业名称', required: true },
  {
    key: 'code',
    label: '企业编码',
    required: true,
    hint: '2-64 位字母、数字、_ 或 -，创建后不可修改',
  },
  { key: 'contact_name', label: '联系人' },
  { key: 'contact_phone', label: '联系电话' },
  { key: 'contract_start', label: '合同开始日期', type: 'date' },
  { key: 'contract_end', label: '合同结束日期', type: 'date' },
  { key: 'remark', label: '备注', type: 'textarea', rows: 3, wide: true },
]);
function create() {
  model.value = {};
  creating.value = true;
}
async function save() {
  busy.value = true;
  try {
    const created = await action(() => request('/api/v1/ops/clients', 'POST', model.value), {
      refresh: query.load,
    });
    creating.value = false;
    selected.value = created.id;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <PageHeader
    title="企业租户"
    description="开通 B 端企业账号、绑定外呼任务，质检通过的线索自动交付到企业门户"
    ><el-button type="primary" @click="create">新建企业租户</el-button></PageHeader
  ><el-card
    ><div class="actions">
      <el-select v-model="status" clearable placeholder="全部状态" style="width: 140px"
        ><el-option label="启用" value="active" /><el-option label="停用" value="disabled"
      /></el-select>
      <el-input
        v-model="keyword"
        clearable
        placeholder="名称或编码"
        style="width: 220px"
        @keyup.enter="query.load()"
      /><el-button @click="query.load()">查询</el-button>
    </div>
    <el-alert v-if="query.error.value" :title="query.error.value" type="error" /><DataGrid
      :rows="query.data.value || []"
      :loading="query.loading.value"
      :pagination="20"
      :columns="[
        { key: 'name', label: '企业名称' },
        { key: 'code', label: '编码' },
        {
          key: 'status',
          label: '状态',
          render: (v) => ({ active: '启用', disabled: '停用' })[v] || v,
        },
        { key: 'contract_end', label: '合同到期', render: (v) => v || '长期' },
        { key: 'campaign_count', label: '绑定任务' },
        { key: 'user_count', label: '账号数' },
        { key: 'recent_leads', label: '近 7 天交付' },
      ]"
      ><template #actions="{ row }"
        ><el-button size="small" type="primary" @click="selected = row.id"
          >管理</el-button
        ></template
      ></DataGrid
    ></el-card
  ><el-dialog v-model="creating" title="新建企业租户" width="680px" destroy-on-close
    ><FieldForm :model="model" :fields="fields" :busy="busy" @submit="save" /></el-dialog
  ><el-drawer
    :model-value="selected !== null"
    size="78%"
    title="企业租户详情"
    destroy-on-close
    @close="
      selected = null;
      query.load();
    "
    ><ClientDetail v-if="selected !== null" :client-id="selected"
  /></el-drawer>
</template>
