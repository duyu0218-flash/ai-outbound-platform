<script setup>
import { ref } from 'vue';
import { useData } from '../data';
import { download, request } from '../session';
import { action } from '../data';
import ContactImport from '../components/ContactImport.vue';
const batches = useData('/api/v1/workspace/contact-batches'),
  jobs = useData('/api/v1/workspace/contact-batches/uploads'),
  importing = ref(false);
async function reload() {
  await Promise.all([batches.load(), jobs.load()]);
}
</script>
<template>
  <PageHeader title="导入批次" description="复用名单批次创建任务，查看导入进度与逐行结果"
    ><el-button @click="reload">刷新进度</el-button
    ><el-button type="primary" @click="importing = true">导入号码</el-button></PageHeader
  ><el-alert
    v-if="batches.error.value || jobs.error.value"
    :title="batches.error.value || jobs.error.value"
    type="error"
  /><el-card
    ><h3>导入任务</h3>
    <el-table :data="jobs.data.value || []"
      ><el-table-column prop="filename" label="文件" /><el-table-column label="状态"
        ><template #default="{ row }">{{
          {
            queued: '排队中',
            processing: '处理中',
            completed: '已完成',
            failed: '失败',
            cancelled: '已取消',
          }[row.state] || row.state
        }}</template></el-table-column
      ><el-table-column prop="error" label="错误原因" /><el-table-column
        prop="created_at"
        label="提交时间"
      /><el-table-column label="已处理 / 总行数"
        ><template #default="{ row }"
          >{{ row.result?.processed_rows ?? (row.state === 'completed' ? row.result?.total : 0) }} /
          {{ row.result?.total ?? '—' }}</template
        ></el-table-column
      ><el-table-column label="恢复与清理"
        ><template #default="{ row }"
          ><el-button
            v-if="row.state === 'failed' && row.retryable"
            link
            @click="
              action(
                () =>
                  request('/api/v1/workspace/contact-batches/uploads/' + row.id + '/retry', 'POST'),
                { refresh: reload },
              )
            "
            >重试导入</el-button
          ><el-button
            v-if="['queued', 'failed'].includes(row.state)"
            link
            type="danger"
            @click="
              action(
                () => request('/api/v1/workspace/contact-batches/uploads/' + row.id, 'DELETE'),
                { confirm: '取消导入并移除上传文件？已导入的联系人会保留。', refresh: reload },
              )
            "
            >取消并移除文件</el-button
          ></template
        ></el-table-column
      ></el-table
    >
    <h3>已完成名单</h3>
    <el-table :data="batches.data.value || []"
      ><el-table-column prop="name" label="批次名称" /><el-table-column label="可选联系人"
        ><template #default="{ row }">{{ row.result.selected_count }}</template></el-table-column
      ><el-table-column label="导入结果"
        ><template #default="{ row }"
          >新增 {{ row.result.created }} · 更新 {{ row.result.updated }} · 跳过
          {{ row.result.skipped }} · 失败 {{ row.result.failed }}</template
        ></el-table-column
      ><el-table-column label="操作"
        ><template #default="{ row }"
          ><el-button
            link
            @click="$router.push({ path: '/admin/campaigns', query: { batch_id: row.id } })"
            >创建外呼任务</el-button
          ><el-button
            link
            @click="
              download(
                '/api/v1/workspace/contact-batches/' + row.id + '/errors',
                '导入错误明细.csv',
              )
            "
            >下载明细</el-button
          ></template
        ></el-table-column
      ></el-table
    ></el-card
  ><ContactImport v-model="importing" @imported="reload" />
</template>
