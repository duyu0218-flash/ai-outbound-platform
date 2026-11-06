<script setup>
import { computed, ref, onMounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useData } from '../data';
import { ops, prefix, perform } from '../operations';
import { download, request } from '../session';
const typeLabels = {
  calls: '话单导出',
  'calls-custom': '自定义话单导出',
  reports: '运营报表',
  dataset: '数据集',
  tasks: '任务归档',
  intents: '意向统计',
  'benchmark:intercept': '拦截统计',
  'benchmark:follow': '跟进统计',
  'benchmark:sms': '短信统计',
  'benchmark:recharge': '充值记录',
  'benchmark:routes': '线路话单',
};
const stateLabels = {
  queued: '排队中',
  processing: '生成中',
  ready: '可下载',
  failed: '生成失败',
  expired: '已过期',
};
const route = useRoute(),
  router = useRouter(),
  rows = ref([]),
  error = ref(''),
  receipt = ref(null),
  tab = ref(route.query.tab === 'downloads' ? 'downloads' : 'unread');
const exports = useData(() => (tab.value === 'downloads' ? '/api/v1/ops/exports' : ''), 2000);
const shown = computed(() =>
  rows.value.filter((r) =>
    tab.value === 'deleted'
      ? r.deleted_at
      : !r.deleted_at && (tab.value === 'read' ? r.read_at : !r.read_at),
  ),
);
async function load() {
  try {
    rows.value = await ops('/notices');
    error.value = '';
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
async function file(row) {
  try {
    await download('/api/v1/ops/exports/' + row.id + '/download');
  } catch (e) {
    error.value = e.message;
  }
}
async function remove(row) {
  await perform(() => ops('/benchmark/notices/' + row.id + '/delete', 'POST'), load);
}
async function loadReceipt() {
  if (!route.query.receipt) return;
  try {
    receipt.value = await request(
      '/api/v1/workspace/contact-batches/' + encodeURIComponent(route.query.receipt) + '/receipt',
    );
  } catch (e) {
    error.value = e.message;
  }
}
watch(() => route.query.receipt, loadReceipt);
onMounted(() => {
  load();
  loadReceipt();
});
</script>
<template>
  <el-dialog :model-value="!!receipt" title="号码导入回执" width="700px" @close="receipt = null"
    ><template v-if="receipt"
      ><p>
        {{ receipt.name }} · 传入{{ receipt.result.total }}，新增{{
          receipt.result.created
        }}，更新{{ receipt.result.updated }}，失败{{ receipt.result.failed }}
      </p>
      <el-table :data="receipt.numbers"
        ><el-table-column prop="phone" label="成功处理号码" /><el-table-column
          prop="currently_blocked"
          label="当前禁呼状态"
      /></el-table>
      <p>最多展示1000个号码，完整批次可在数据集中查询。</p>
      <JsonView :value="receipt.result.errors" /></template
  ></el-dialog>
  <PageHeader title="消息中心" description="导出文件、未读通知、已读记录与已删除消息"
    ><el-button
      @click="
        load();
        exports.load();
      "
      >刷新通知</el-button
    ></PageHeader
  >
  <el-alert
    v-if="error || exports.error.value"
    :title="error || exports.error.value"
    type="error"
  />
  <el-tabs v-model="tab"
    ><el-tab-pane name="downloads" label="下载" /><el-tab-pane
      name="unread"
      label="未读" /><el-tab-pane name="read" label="已读" /><el-tab-pane
      name="deleted"
      label="已删除"
  /></el-tabs>
  <el-card v-if="tab === 'downloads'"
    ><el-table :data="exports.data.value || []" border
      ><el-table-column prop="id" label="ID" /><el-table-column label="类型"
        ><template #default="{ row }">{{
          typeLabels[row.kind] || row.kind
        }}</template></el-table-column
      ><el-table-column label="状态"
        ><template #default="{ row }">{{
          stateLabels[row.state] || row.state
        }}</template></el-table-column
      ><el-table-column prop="queue_position" label="队列排位" /><el-table-column
        prop="error"
        label="内容"
      /><el-table-column prop="created_at" label="日期" /><el-table-column
        prop="expires_at"
        label="下载有效期"
      /><el-table-column label="文件与操作"
        ><template #default="{ row }"
          ><el-button :disabled="row.state !== 'ready'" link @click="file(row)">下载文件</el-button
          ><span v-if="['queued', 'processing'].includes(row.state)">正在生成</span></template
        ></el-table-column
      ></el-table
    ></el-card
  >
  <el-card v-else
    ><DataGrid
      :rows="shown"
      :pagination="20"
      :columns="[
        { key: 'id', label: 'ID' },
        { key: 'title', label: '内容' },
        { key: 'created_at', label: '日期', date: true },
        { key: 'read_at', label: '已读时间', date: true },
      ]"
      ><template #actions="{ row }"
        ><el-button v-if="!row.deleted_at" link @click="open(row)">查看事项</el-button
        ><el-button v-if="!row.deleted_at" link type="danger" @click="remove(row)"
          >删除消息</el-button
        ></template
      ></DataGrid
    ></el-card
  >
</template>
