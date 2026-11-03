<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { request, session } from '../session';
import { formatDate } from '../api';
const props = defineProps({
    libraryId: String,
    libraryRevision: Number,
    categories: { type: Array, default: () => [] },
  }),
  emit = defineEmits(['changed']);
const jobs = ref([]),
  selected = ref(null),
  segments = ref([]),
  file = ref(null),
  size = ref(800),
  overlap = ref(80),
  category = ref(''),
  busy = ref(false),
  error = ref('');
const names = {
  queued: '排队中',
  parsing: '正在解析',
  ready: '待确认分段',
  committed: '已生成草稿',
  failed: '解析失败',
  cancelled: '已移除',
};
const base = () => `/api/v1/workspace/libraries/${props.libraryId}/documents`;
let timer,
  disposed = false,
  polling = false,
  key = '',
  epoch = session.epoch;
const valid = () => !disposed && session.epoch === epoch;
async function load() {
  const rows = await request(base());
  if (valid()) jobs.value = rows;
}
async function run(fn) {
  if (busy.value) return;
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
async function open(row) {
  const value = await request(base() + '/' + row.id);
  if (!valid()) return;
  selected.value = value;
  segments.value = structuredClone(value.segments || []);
}
function choose(event) {
  file.value = event.target.files?.[0] || null;
  event.target.value = '';
  key = crypto.randomUUID();
  error.value = '';
}
async function upload() {
  await run(async () => {
    if (!file.value) throw Error('请先选择文档');
    if (file.value.size > 2 * 1024 * 1024) throw Error('文档最大2MB，请拆分文件');
    const body = new FormData();
    body.append('file', file.value);
    body.append('request_key', (key ||= crypto.randomUUID()));
    body.append('split_size', String(size.value));
    body.append('overlap', String(overlap.value));
    body.append('category', category.value);
    const job = await request(base(), 'POST', body);
    if (!valid()) return;
    file.value = null;
    await open(job);
    await load();
  });
}
async function command(row, action) {
  await run(async () => {
    const value = await request(`${base()}/${row.id}/${action}`, 'POST', {
      expected_revision: row.revision,
    });
    if (!valid()) return;
    await open(value);
    await load();
  });
}
async function confirm() {
  await run(async () => {
    const value = await request(`${base()}/${selected.value.id}/confirm`, 'POST', {
      expected_revision: selected.value.revision,
      expected_library_revision: props.libraryRevision,
      segments: segments.value,
    });
    if (!valid()) return;
    selected.value = value;
    segments.value = structuredClone(value.segments || []);
    emit('changed');
    await load();
  });
}
async function tick() {
  if (polling || busy.value || !valid()) return;
  polling = true;
  try {
    if (
      jobs.value.some((x) => ['queued', 'parsing'].includes(x.state)) ||
      (selected.value && ['queued', 'parsing'].includes(selected.value.state))
    ) {
      await load();
      if (selected.value && ['queued', 'parsing'].includes(selected.value.state)) {
        const id = selected.value.id,
          value = await request(base() + '/' + id);
        if (valid() && selected.value?.id === id) {
          selected.value = value;
          segments.value = structuredClone(value.segments || []);
        }
      }
    }
  } catch (e) {
    if (valid()) error.value = e.message;
  } finally {
    polling = false;
  }
}
onMounted(() => {
  run(load);
  timer = setInterval(tick, 1500);
});
onBeforeUnmount(() => {
  disposed = true;
  clearInterval(timer);
});
</script>
<template>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-alert
    title="解析、确认草稿、发布知识库分开操作。解析完成不会自动入库或影响运行任务。"
    :closable="false"
  />
  <p>
    支持UTF-8
    TXT、Markdown、DOCX和有文本层的PDF；最大2MB、PDF最多200页、提取文字最多10万字符。扫描件与图片需先转换文字。
  </p>
  <el-card>
    <div class="form-grid">
      <el-form-item label="选择知识文档"
        ><input
          type="file"
          aria-label="上传知识文档"
          accept=".txt,.md,.docx,.pdf"
          :disabled="busy"
          @change="choose"
      /></el-form-item>
      <el-form-item label="分段所属分类"
        ><el-select v-model="category" aria-label="文档分类" clearable :disabled="busy"
          ><el-option
            v-for="c in categories"
            :key="c.name"
            :label="c.name"
            :value="c.name" /></el-select
      ></el-form-item>
      <el-form-item label="每段最大字符"
        ><el-input-number
          v-model="size"
          aria-label="分段长度"
          :min="200"
          :max="2000"
          :disabled="busy"
      /></el-form-item>
      <el-form-item label="相邻分段重叠字符"
        ><el-input-number
          v-model="overlap"
          aria-label="分段重叠"
          :min="0"
          :max="Math.min(200, Math.floor(size / 3))"
          :disabled="busy"
      /></el-form-item>
    </div>
    <p v-if="file">已选择：{{ file.name }}</p>
    <el-button type="primary" :loading="busy" :disabled="!file" @click="upload">开始解析</el-button
    ><el-button :disabled="busy" @click="run(load)">刷新文档作业</el-button>
  </el-card>
  <el-table :data="jobs">
    <el-table-column prop="filename" label="文档" /><el-table-column label="状态"
      ><template #default="{ row }">{{ names[row.state] || row.state }}</template></el-table-column
    >
    <el-table-column label="解析阶段"
      ><template #default="{ row }"
        ><span>{{
          row.state === 'queued'
            ? '等待处理'
            : row.state === 'parsing'
              ? row.progress >= 70
                ? '生成分段'
                : '提取文字'
              : row.progress === 100
                ? '解析完成'
                : '已停止'
        }}</span></template
      ></el-table-column
    >
    <el-table-column prop="error" label="失败原因" /><el-table-column label="更新时间"
      ><template #default="{ row }">{{ formatDate(row.updated_at) }}</template></el-table-column
    >
    <el-table-column label="操作" min-width="220"
      ><template #default="{ row }">
        <el-button link :disabled="busy" @click="run(() => open(row))">{{
          row.state === 'committed' ? '查看来源' : '查看分段'
        }}</el-button>
        <el-button
          v-if="row.state === 'failed'"
          link
          :disabled="busy"
          @click="command(row, 'retry')"
          >重新解析</el-button
        >
        <el-button
          v-if="['queued', 'ready', 'failed'].includes(row.state)"
          link
          type="danger"
          :disabled="busy"
          @click="command(row, 'cancel')"
          >移除作业</el-button
        >
      </template></el-table-column
    >
  </el-table>
  <el-card v-if="selected" class="document-preview" aria-label="文档分段预览">
    <h3>{{ selected.filename }} · {{ names[selected.state] }}</h3>
    <p>文件指纹：{{ selected.file_sha }}</p>
    <p>分段内容可核对来源。解析后的原始文本保留作证据，上传原文件在解析完成后移除。</p>
    <template v-if="selected.state === 'ready'">
      <p>
        保留
        {{ segments.length }} 个分段。可修订标题和正文、移除不需要的内容；确认后仅生成知识草稿。
      </p>
      <el-card v-for="(part, index) in segments" :key="part.segment_index" class="segment-card">
        <p>原文分段 {{ part.segment_index }} · {{ part.source }}</p>
        <el-input
          v-model="part.name"
          :aria-label="'分段标题' + part.segment_index"
          :disabled="busy"
        /><el-input
          v-model="part.content"
          type="textarea"
          :rows="5"
          :aria-label="'分段正文' + part.segment_index"
          :disabled="busy"
        /><el-button link type="danger" :disabled="busy" @click="segments.splice(index, 1)"
          >不导入该分段</el-button
        >
      </el-card>
      <el-button type="primary" :loading="busy" :disabled="!segments.length" @click="confirm"
        >确认分段并生成草稿</el-button
      >
    </template>
    <template v-else-if="selected.state === 'committed'">
      <el-alert
        title="已生成草稿。请回到知识条目核对，再发布知识库。重复确认不会创建第二份条目。"
        type="success"
        :closable="false"
      />
      <el-collapse
        ><el-collapse-item
          v-for="part in selected.original_segments"
          :key="part.segment_index"
          :title="part.name"
          ><p class="source-text">{{ part.content }}</p></el-collapse-item
        ></el-collapse
      >
    </template>
    <el-empty
      v-else
      :description="selected.error || '解析作业尚未完成，可离开后从作业列表继续查看'"
    />
  </el-card>
</template>
<style scoped>
.document-preview,
.segment-card {
  margin-top: 16px;
}
.segment-card .el-textarea {
  margin: 12px 0;
}
.source-text {
  white-space: pre-wrap;
}
</style>
