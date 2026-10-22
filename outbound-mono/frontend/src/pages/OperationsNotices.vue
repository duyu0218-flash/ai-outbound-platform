<script setup>
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ops, prefix } from '../operations';
const rows = ref([]),
  error = ref(''),
  router = useRouter();
async function load() {
  try {
    rows.value = await ops('/notices');
  } catch (e) {
    error.value = e.message;
  }
}
async function open(row) {
  try {
    await ops('/notices/' + row.id + '/read', 'POST');
    await load();
    router.push(prefix() + row.path);
  } catch (e) {
    error.value = e.message;
  }
}
onMounted(load);
</script>
<template>
  <PageHeader title="通知中心" description="线索分配、到期跟进和导出结果"
    ><el-button @click="load">刷新通知</el-button></PageHeader
  ><el-alert v-if="error" :title="error" type="error" /><el-card
    ><DataGrid
      :rows="rows"
      :pagination="20"
      :columns="[
        { key: 'title', label: '通知' },
        { key: 'created_at', label: '时间', date: true },
        { key: 'read_at', label: '已读时间', date: true },
      ]"
      ><template #actions="{ row }"
        ><el-button link @click="open(row)">查看事项</el-button></template
      ></DataGrid
    ></el-card
  >
</template>
