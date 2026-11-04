<script setup>
import { ref } from 'vue';
import { useData, action } from '../data';
import { ops } from '../operations';
const keyword = ref(''),
  page = ref(1),
  selected = ref(null),
  status = ref('pending'),
  note = ref('');
const labels = { pending: '待添加', added: '已添加', declined: '已拒绝' };
const rows = useData(
  () =>
    '/api/v1/ops/leads?view=mine&size=30&page=' +
    page.value +
    '&keyword=' +
    encodeURIComponent(keyword.value),
);
function edit(row) {
  selected.value = row;
  status.value = row.wecom_status;
  note.value = '';
}
async function save() {
  const row = selected.value;
  const saved = await action(
    () =>
      ops('/leads/' + row.contact_id, 'PATCH', {
        action: 'wecom',
        wecom_status: status.value,
        note: note.value,
        event_key: crypto.randomUUID(),
        revision: row.revision,
      }),
    { refresh: rows.load },
  );
  if (saved) selected.value = null;
}
</script>
<template>
  <PageHeader title="企微添加" description="登记本人跟进线索的企业微信添加结果" /><el-alert
    v-if="rows.error.value"
    type="error"
    :title="rows.error.value"
  />
  <el-card
    ><el-input
      aria-label="查找企微线索"
      v-model="keyword"
      placeholder="按客户姓名或号码搜索"
      clearable
      @input="page = 1"
    />
    <el-table :data="rows.data.value?.rows || []" row-key="contact_id"
      ><el-table-column prop="name" label="客户" /><el-table-column label="添加结果"
        ><template #default="{ row }">{{ labels[row.wecom_status] }}</template></el-table-column
      ><el-table-column label="操作"
        ><template #default="{ row }"
          ><el-button @click="edit(row)">登记添加结果</el-button></template
        ></el-table-column
      ></el-table
    >
    <el-pagination
      v-model:current-page="page"
      :page-size="30"
      :total="rows.data.value?.total || 0"
      layout="prev,pager,next,total"
    /> </el-card
  ><el-dialog :model-value="!!selected" title="登记添加结果" @close="selected = null"
    ><el-form label-position="top"
      ><el-form-item label="添加结果"
        ><el-select v-model="status" aria-label="企微添加结果"
          ><el-option
            v-for="(v, k) in labels"
            :key="k"
            :value="k"
            :label="v" /></el-select></el-form-item
      ><el-form-item label="备注"
        ><el-input
          v-model="note"
          aria-label="企微添加备注"
          maxlength="2000" /></el-form-item></el-form
    ><template #footer><el-button @click="save">保存结果</el-button></template></el-dialog
  >
</template>
