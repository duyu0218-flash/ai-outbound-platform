<script setup>
import { ref, reactive } from 'vue';
import { useData } from '../data';
import { query, ops, perform } from '../operations';
const q = reactive({ phone: '', batch: '', page: 1 }),
  applied = ref({ ...q }),
  picked = ref([]),
  purpose = ref('task');
const data = useData(() => '/api/v1/ops/benchmark/dataset?' + query(applied.value)),
  batches = useData('/api/v1/workspace/contact-batches');
async function exportData() {
  await perform(() =>
    ops('/exports', 'POST', {
      kind: 'dataset',
      query: applied.value,
      request_key: crypto.randomUUID(),
    }),
  );
}
function search() {
  applied.value = { ...q };
  data.load();
}
</script>
<template>
  <PageHeader title="数据集" description="从号码池查看触达历史并勾选创建任务"
    ><el-button @click="exportData">导出数据</el-button
    ><el-button @click="$router.push('/admin/contacts')">导入数据</el-button
    ><el-button
      type="primary"
      :disabled="purpose !== 'task' || !picked.length"
      @click="
        $router.push({
          path: '/admin/campaigns',
          query: { contact_ids: picked.map((r) => r.id).join(',') },
        })
      "
      >创建任务</el-button
    ></PageHeader
  ><el-alert v-if="data.error.value" type="error" :title="data.error.value" /><el-card
    ><div class="toolbar">
      <el-select v-model="purpose" aria-label="筛选用途"
        ><el-option value="task" label="创建任务" /><el-option
          value="history"
          label="查看历史" /></el-select
      ><el-input v-model="q.phone" placeholder="电话" aria-label="数据集电话" /><el-select
        v-model="q.batch"
        clearable
        aria-label="数据集批次"
        ><el-option
          v-for="b in batches.data.value || []"
          :key="b.id"
          :label="b.name"
          :value="b.id" /></el-select
      ><el-button
        @click="
          q.phone = '';
          q.batch = '';
          q.page = 1;
          search();
        "
        >重置</el-button
      ><el-button
        type="primary"
        @click="
          q.page = 1;
          search();
        "
        >查询</el-button
      ><el-button @click="data.load">刷新</el-button>
    </div>
    <el-table :data="data.data.value?.rows || []" border @selection-change="picked = $event"
      ><el-table-column v-if="purpose === 'task'" type="selection" /><el-table-column
        v-for="[key, label] in [
          ['phone', '电话'],
          ['batches', '批次号'],
          ['city', '号码归属地'],
          ['grade', '最新评级'],
          ['status', '拨打状态'],
          ['hangup_reason', '挂机原因'],
          ['longest', '最长通话时长'],
          ['total_seconds', '累计通话时长'],
          ['attempts', '呼叫次数'],
          ['connected', '通话次数'],
          ['last_call', '最近通话时间'],
          ['created_at', '创建时间'],
        ]"
        :key="key"
        :prop="key"
        :label="label"
        min-width="145"
      /><el-table-column label="操作" fixed="right" width="120"
        ><template #default="{ row }"
          ><el-button
            link
            @click="$router.push({ path: '/admin/tasks/record', query: { contact_id: row.id } })"
            >触达历史</el-button
          ></template
        ></el-table-column
      ></el-table
    ><el-pagination
      :current-page="q.page"
      :page-size="20"
      :total="data.data.value?.total || 0"
      layout="prev,pager,next,total"
      @current-change="
        q.page = $event;
        search();
      "
  /></el-card>
</template>
