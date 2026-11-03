<script setup>
import { ref, onMounted, onBeforeUnmount, computed } from 'vue';
import { useRouter } from 'vue-router';
import { request, session } from '../session';
import { formatDate } from '../api';
import CallDetails from './CallDetails.vue';
const props = defineProps({ libraryId: String, categories: { type: Array, default: () => [] } }),
  emit = defineEmits(['changed', 'open-library']);
const router = useRouter(),
  rows = ref([]),
  page = ref(1),
  total = ref(0),
  state = ref('pending'),
  source = ref('call'),
  keyword = ref(''),
  busy = ref(false),
  error = ref(''),
  notice = ref(''),
  selected = ref(null),
  entry = ref({}),
  editing = ref(false),
  confirmed = ref(false),
  resolution = ref(null),
  reason = ref(''),
  evidence = ref(null);
let disposed = false;
const epoch = session.epoch,
  valid = () => !disposed && epoch === session.epoch;
const labels = { pending: '待整理', adopted: '已生成草稿', dismissed: '已忽略' };
const fields = computed(() => [
  { key: 'name', label: '标准问题', required: true },
  {
    key: 'category',
    label: '知识分类',
    options: props.categories.map((c) => ({ value: c.name, label: c.name })),
    clearable: true,
  },
  { key: 'content', label: '经人工确认的标准答案', type: 'textarea', rows: 7, required: true },
  { key: 'similar_questions', label: '相似问法', type: 'textarea', rows: 2 },
  { key: 'keywords', label: '检索关键词' },
  { key: 'source', label: '知识来源', type: 'textarea', rows: 2 },
]);
async function load() {
  const v = await request(
    `/api/v1/workspace/knowledge-gaps?state=${state.value}&source=${source.value}&keyword=${encodeURIComponent(keyword.value)}&page=${page.value}&size=50`,
  );
  if (valid()) {
    rows.value = v.rows;
    total.value = v.total;
  }
}
async function run(fn) {
  busy.value = true;
  error.value = '';
  try {
    await fn();
  } catch (e) {
    if (valid()) error.value = e.message;
  } finally {
    if (valid()) busy.value = false;
  }
}
function edit(row) {
  if (!props.libraryId) {
    error.value = '请先进入目标知识库的待补知识页，再整理为知识草稿。';
    return;
  }
  selected.value = row;
  confirmed.value = false;
  entry.value = {
    name: row.question.slice(0, 150),
    content: '',
    similar_questions: row.question,
    keywords: '',
    category: '',
    enabled: true,
    source: `${row.source_type === 'call' ? '通话' : '测试会话'} ${row.source_id} · 话术版本 ${row.context?.script_version ?? '历史未知'}`,
  };
  editing.value = true;
}
async function adopt() {
  await run(async () => {
    await request(`/api/v1/workspace/knowledge-gaps/${selected.value.id}/adopt`, 'POST', {
      library_id: props.libraryId,
      expected_revision: selected.value.revision,
      draft: entry.value,
      confirmed: confirmed.value,
    });
    if (!valid()) return;
    editing.value = false;
    notice.value = '已生成知识草稿，核对并发布知识库后才供新任务引用。';
    emit('changed');
    await load();
  });
}
async function disposition() {
  await run(async () => {
    await request(
      `/api/v1/workspace/knowledge-gaps/${resolution.value.row.id}/${resolution.value.action}`,
      'POST',
      { expected_revision: resolution.value.row.revision, reason: reason.value },
    );
    if (!valid()) return;
    resolution.value = null;
    await load();
  });
}
async function show(row) {
  await run(async () => {
    if (row.source_type === 'call') {
      const call = await request('/api/v1/calls/' + row.source_id);
      if (valid()) evidence.value = call;
    } else router.push({ path: '/admin/business-debug', query: { session_id: row.source_id } });
  });
}
onMounted(() => run(load));
onBeforeUnmount(() => {
  disposed = true;
});
</script>
<template>
  <el-alert
    title="此处是已发布知识未命中的待确认问题，不等于已证实的知识缺陷。请先核对来源证据，整理后的答案仅进入当前知识库草稿。"
    :closable="false"
  />
  <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-alert
    v-if="notice"
    :title="notice"
    type="success"
    :closable="false"
  />
  <div class="actions gap-filters">
    <el-select v-model="state" aria-label="问题整理状态" style="width: 170px"
      ><el-option label="待整理" value="pending" /><el-option
        label="已生成草稿"
        value="adopted" /><el-option label="已忽略" value="dismissed" /><el-option
        label="全部状态"
        value="all"
    /></el-select>
    <el-select v-model="source" aria-label="问题来源" style="width: 170px"
      ><el-option label="业务通话" value="call" /><el-option
        label="测试会话"
        value="debug" /><el-option label="全部来源" value="all"
    /></el-select>
    <el-input
      v-model="keyword"
      aria-label="搜索待整理问题"
      placeholder="搜索客户问题"
      style="width: 260px"
      clearable
    />
    <el-button
      :loading="busy"
      @click="
        page = 1;
        run(load);
      "
      >筛选问题</el-button
    >
  </div>
  <el-table :data="rows" v-loading="busy">
    <el-table-column prop="question" label="客户问题" min-width="240" />
    <el-table-column label="来源"
      ><template #default="{ row }"
        >{{
          row.source_type === 'call'
            ? '业务通话'
            : row.source_type === 'debug'
              ? '测试会话'
              : '人工录入'
        }}<el-tag v-if="row.context?.simulated" type="info">模拟环境</el-tag></template
      ></el-table-column
    >
    <el-table-column label="话术版本"
      ><template #default="{ row }">{{
        row.context?.script_version ? 'v' + row.context.script_version : '历史未知'
      }}</template></el-table-column
    >
    <el-table-column label="状态"
      ><template #default="{ row }">{{ labels[row.state] || row.state }}</template></el-table-column
    >
    <el-table-column prop="resolution" label="处理说明" /><el-table-column label="记录时间"
      ><template #default="{ row }">{{ formatDate(row.created_at) }}</template></el-table-column
    >
    <el-table-column label="操作" min-width="280"
      ><template #default="{ row }">
        <el-button
          v-if="['call', 'debug'].includes(row.source_type)"
          link
          :disabled="busy"
          @click="show(row)"
          >查看来源证据</el-button
        >
        <template v-if="row.state === 'pending'"
          ><el-button link type="primary" @click="edit(row)">核对并整理</el-button
          ><el-button
            link
            @click="
              reason = '';
              resolution = { row, action: 'dismiss' };
            "
            >忽略问题</el-button
          ></template
        >
        <el-button
          v-if="row.state === 'dismissed'"
          link
          @click="
            reason = '';
            resolution = { row, action: 'reopen' };
          "
          >重新整理</el-button
        >
        <el-button v-if="row.state === 'adopted'" link @click="emit('open-library', row.library_id)"
          >查看知识草稿</el-button
        >
      </template></el-table-column
    >
  </el-table>
  <p>共 {{ total }} 条</p>
  <Pager
    :page="page"
    :size="50"
    :count="rows.length"
    @change="
      page = $event;
      run(load);
    "
  />
  <el-dialog
    v-model="editing"
    title="核对问题并整理知识"
    width="min(850px,95vw)"
    :close-on-click-modal="false"
  >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p>原问题：{{ selected?.question }}</p>
    <el-button v-if="selected" link @click="show(selected)">查看来源证据</el-button>
    <FieldForm :model="entry" :fields="fields" hide-submit />
    <el-checkbox v-model="confirmed" aria-label="确认已人工核对答案"
      >我已核对来源与标准答案，确认仅生成知识草稿</el-checkbox
    >
    <template #footer
      ><el-button :disabled="busy" @click="editing = false">取消整理</el-button
      ><el-button type="primary" :loading="busy" :disabled="!confirmed" @click="adopt"
        >保存为知识草稿</el-button
      ></template
    >
  </el-dialog>
  <el-dialog
    :model-value="!!resolution"
    :title="resolution?.action === 'reopen' ? '重新整理问题' : '忽略问题'"
    width="550px"
    @close="resolution = null"
  >
    <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-input
      v-model="reason"
      aria-label="问题处理原因"
      type="textarea"
      :rows="4"
      placeholder="填写处理原因，历史记录会保留"
    />
    <template #footer
      ><el-button :loading="busy" @click="disposition">保存处理结果</el-button></template
    >
  </el-dialog>
  <CallDetails v-if="evidence" :call="evidence" role="admin" @close="evidence = null" />
</template>
<style scoped>
.gap-filters {
  margin: 18px 0;
}
</style>
