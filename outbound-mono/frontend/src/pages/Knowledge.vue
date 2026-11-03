<script setup>
import { ref, computed } from 'vue';
import { request } from '../session';
import { formatDate } from '../api';
import { useData } from '../data';
const query = useData('/api/v1/workspace/libraries'),
  selected = ref(null),
  model = ref({}),
  tab = ref('entries'),
  editing = ref(null),
  entry = ref({}),
  entryOpen = ref(false),
  createOpen = ref(false),
  name = ref(''),
  busy = ref(false),
  error = ref(''),
  keyword = ref(''),
  category = ref(''),
  sample = ref(''),
  hits = ref(null),
  draftProbe = ref(false),
  history = ref([]);
const clone = (v) => JSON.parse(JSON.stringify(v));
const fields = [
  { key: 'name', label: '标准问题 / 文档标题', required: true },
  { key: 'category', label: '分类路径', placeholder: '产品/办理条件' },
  { key: 'similar_questions', label: '相似问法', type: 'textarea', rows: 2 },
  { key: 'content', label: '标准答案 / 文档正文', type: 'textarea', rows: 8, required: true },
  { key: 'keywords', label: '检索关键词', placeholder: '多个关键词用空格分隔' },
  { key: 'source', label: '内容来源' },
  { key: 'valid_from', label: '生效时间（UTC）', placeholder: '2026-11-03T00:00:00' },
  { key: 'valid_until', label: '失效时间（UTC）', placeholder: '2027-01-01T00:00:00' },
  { key: 'enabled', label: '参与发布', type: 'boolean' },
];
const categories = computed(() => [
  ...new Set((selected.value?.items || []).map((v) => v.draft.category || '未分类')),
]);
const entries = computed(() =>
  (selected.value?.items || []).filter(
    (v) =>
      (!category.value || (v.draft.category || '未分类') === category.value) &&
      (!keyword.value ||
        [v.draft.name, v.draft.content, v.draft.keywords].join(' ').includes(keyword.value)),
  ),
);
async function run(fn) {
  busy.value = true;
  error.value = '';
  try {
    await fn();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function reload() {
  selected.value = await request('/api/v1/workspace/libraries/' + selected.value.object_id);
  model.value = clone(selected.value.draft);
  await query.load();
}
async function open(lib) {
  await run(async () => {
    selected.value = await request('/api/v1/workspace/libraries/' + lib.object_id);
    model.value = clone(selected.value.draft);
    history.value = await request(`/api/v1/workspace/libraries/${lib.object_id}/history`);
    tab.value = 'entries';
    category.value = '';
    hits.value = null;
  });
}
async function create() {
  await run(async () => {
    const lib = await request('/api/v1/workspace/libraries', 'POST', { name: name.value });
    createOpen.value = false;
    await query.load();
    await open(lib);
  });
}
function edit(row) {
  editing.value = row;
  entry.value = row
    ? clone(row.draft)
    : {
        name: '',
        category: '',
        similar_questions: '',
        content: '',
        source: '',
        keywords: '',
        valid_from: '',
        valid_until: '',
        enabled: true,
      };
  entryOpen.value = true;
}
async function saveEntry() {
  await run(async () => {
    await request(
      `/api/v1/workspace/libraries/${selected.value.object_id}/items/${editing.value?.object_id || crypto.randomUUID()}`,
      'PUT',
      { draft: entry.value, expected_revision: editing.value?.revision || 0 },
    );
    entryOpen.value = false;
    await reload();
  });
}
async function saveMeta() {
  await request(`/api/v1/workspace/libraries/${selected.value.object_id}`, 'PUT', {
    draft: model.value,
    expected_revision: selected.value.revision,
  });
  await reload();
}
async function publish() {
  await run(async () => {
    await saveMeta();
    await request(`/api/v1/workspace/libraries/${selected.value.object_id}/publish`, 'POST', {
      expected_revision: selected.value.revision,
    });
    await reload();
    history.value = await request(
      `/api/v1/workspace/libraries/${selected.value.object_id}/history`,
    );
  });
}
async function state(value) {
  await run(async () => {
    await request(`/api/v1/workspace/libraries/${selected.value.object_id}/state`, 'POST', {
      state: value,
      expected_revision: selected.value.revision,
    });
    await reload();
  });
}
async function document(event) {
  const file = event.target.files?.[0];
  event.target.value = '';
  if (!file) return;
  await run(async () => {
    if (!/\.(txt|md)$/i.test(file.name) || file.size > 80000)
      throw Error('支持不超过80KB的UTF-8 txt/md文件；PDF、扫描件请先转换为文本。');
    const content = await file.text();
    if (content.includes('\uFFFD') || content.length > 20000)
      throw Error('文件须为UTF-8编码，正文不超过20000字符。');
    edit();
    entry.value.name = file.name.replace(/\.[^.]+$/, '');
    entry.value.source = '文件：' + file.name;
    entry.value.content = content;
  });
}
</script>
<template>
  <PageHeader title="知识库" description="维护可追溯的业务知识，审核发布后关联话术"
    ><el-button
      type="primary"
      @click="
        name = '';
        createOpen = true;
      "
      >新建知识库</el-button
    ></PageHeader
  ><el-alert
    v-if="error || query.error.value"
    type="error"
    :title="error || query.error.value"
    :closable="false"
  />
  <template v-if="!selected"
    ><el-alert
      v-if="query.data.value?.legacy_count"
      :title="`检测到 ${query.data.value.legacy_count} 条历史知识，可复制到草稿库核对。每次最多迁移500条，原记录保留。`"
      :closable="false"
      ><el-button
        @click="
          run(async () => {
            const lib = await request('/api/v1/workspace/libraries/import-legacy', 'POST');
            await query.load();
            await open(lib);
          })
        "
        >迁移历史知识</el-button
      ></el-alert
    >
    <div class="script-grid">
      <el-card v-for="lib in query.data.value?.rows || []" :key="lib.object_id"
        ><template #header
          ><strong>{{ lib.name }}</strong></template
        >
        <p>{{ lib.draft.description || '尚未填写知识库说明' }}</p>
        <el-tag>{{
          { draft: '草稿', published: '已发布', review: '待审核', disabled: '已停用' }[lib.state]
        }}</el-tag>
        <p>已发布 v{{ lib.published_version }}</p>
        <p>{{ lib.entry_count || 0 }} 条知识 · 负责人：{{ lib.owner_name || '未指定' }}</p>
        <small>{{ formatDate(lib.updated_at) }}</small>
        <el-button type="primary" plain @click="open(lib)">管理知识</el-button></el-card
      >
    </div>
    <el-empty
      v-if="!query.loading.value && !query.data.value?.rows?.length"
      description="新建知识库，添加问答或导入文本后发布"
  /></template>
  <template v-else
    ><div class="actions">
      <el-button
        @click="
          selected = null;
          query.load();
        "
        >返回知识库列表</el-button
      ><strong>{{ selected.name }}</strong
      ><el-tag>已发布 v{{ selected.published_version }}</el-tag
      ><el-button :loading="busy" @click="state('review')">提交审核</el-button
      ><el-button type="primary" :loading="busy" @click="publish">发布知识库</el-button
      ><el-button type="danger" plain :disabled="busy" @click="state('disabled')">停用</el-button>
    </div>
    <el-tabs v-model="tab"
      ><el-tab-pane label="知识条目" name="entries" /><el-tab-pane
        label="知识库信息"
        name="settings" /><el-tab-pane label="检索测试" name="probe" /><el-tab-pane
        label="发布历史"
        name="history"
    /></el-tabs>
    <el-card v-if="tab === 'entries'"
      ><div class="actions">
        <el-button type="primary" @click="edit()">添加问答</el-button
        ><label
          >导入文本文件<input
            aria-label="导入知识文件"
            type="file"
            accept=".txt,.md"
            @change="document" /></label
        ><el-input
          v-model="keyword"
          aria-label="搜索知识"
          placeholder="搜索问题、答案、关键词"
          clearable
          style="width: 280px"
        />
      </div>
      <div class="knowledge-layout">
        <aside>
          <el-button link @click="category = ''">全部分类</el-button>
          <p v-for="c in categories" :key="c">
            <el-button link :type="category === c ? 'primary' : ''" @click="category = c">{{
              c
            }}</el-button>
          </p>
        </aside>
        <el-table :data="entries"
          ><el-table-column label="标准问题 / 标题"
            ><template #default="{ row }">{{ row.draft.name }}</template></el-table-column
          ><el-table-column label="分类"
            ><template #default="{ row }">{{
              row.draft.category || '未分类'
            }}</template></el-table-column
          ><el-table-column label="参与发布"
            ><template #default="{ row }">{{
              row.draft.enabled ? '是' : '否'
            }}</template></el-table-column
          ><el-table-column label="来源"
            ><template #default="{ row }">{{
              row.draft.source || '人工录入'
            }}</template></el-table-column
          ><el-table-column label="操作"
            ><template #default="{ row }"
              ><el-button link @click="edit(row)">编辑知识</el-button></template
            ></el-table-column
          ></el-table
        >
      </div>
      <p>
        文本文件导入后先进入条目草稿。知识库发布前不会影响现有任务；停用条目后需重新发布知识库。
      </p></el-card
    >
    <el-card v-if="tab === 'settings'"
      ><FieldForm
        :model="model"
        :fields="[
          { key: 'name', label: '知识库名称', required: true },
          { key: 'description', label: '适用范围与维护说明', type: 'textarea' },
        ]"
        :busy="busy"
        @submit="run(saveMeta)"
    /></el-card>
    <el-card v-if="tab === 'probe'"
      ><el-switch
        v-model="draftProbe"
        active-text="测试当前草稿"
        inactive-text="测试已发布版本"
      /><el-input
        v-model="sample"
        aria-label="知识检索问题"
        placeholder="输入客户可能提出的问题"
        style="margin: 16px 0"
      /><el-button
        :loading="busy"
        @click="
          run(async () => {
            hits = await request(
              `/api/v1/workspace/libraries/${selected.object_id}/probe`,
              'POST',
              { query: sample, draft: draftProbe },
            );
          })
        "
        >检索测试</el-button
      >
      <p>使用关键词和中文片段匹配，展示最多3条结果；未调用外部模型。</p>
      <template v-if="hits"
        ><el-alert :title="hits.scope" :closable="false" /><el-empty
          v-if="!hits.hits.length"
          description="未命中知识，请补充问法或关键词；实际对话应澄清或转人工"
        /><el-card v-for="hit in hits.hits" :key="hit.id"
          ><strong>{{ hit.title }}</strong>
          <p>{{ hit.content }}</p>
          <small>来源：{{ hit.source || '人工录入' }} · v{{ hit.version }}</small></el-card
        ></template
      ></el-card
    >
    <el-card v-if="tab === 'history'"
      ><el-table :data="history"
        ><el-table-column prop="version" label="发布版本" /><el-table-column
          prop="published_at"
          label="发布时间"
        /><el-table-column label="操作"
          ><template #default="{ row }"
            ><el-button
              link
              @click="
                run(async () => {
                  await request(
                    `/api/v1/workspace/libraries/${selected.object_id}/restore`,
                    'POST',
                    { version: row.version, expected_revision: selected.revision },
                  );
                  await reload();
                })
              "
              >恢复为草稿</el-button
            ></template
          ></el-table-column
        ></el-table
      >
      <p>恢复会在当前草稿重建所选版本条目，需重新发布，任务快照保持原版本。</p></el-card
    >
  </template>
  <el-dialog v-model="createOpen" title="新建知识库" width="520px"
    ><el-input v-model="name" aria-label="知识库名称" /><template #footer
      ><el-button type="primary" :loading="busy" @click="create">创建知识库</el-button></template
    ></el-dialog
  >
  <el-dialog v-model="entryOpen" title="编辑知识条目" width="min(880px,95vw)"
    ><FieldForm :model="entry" :fields="fields" :busy="busy" @submit="saveEntry"
  /></el-dialog>
</template>
<style scoped>
.knowledge-layout {
  display: grid;
  grid-template-columns: 180px 1fr;
  gap: 24px;
  margin-top: 20px;
}
aside {
  border-right: 1px solid #e2e8f0;
}
small {
  color: #64748b;
}
</style>
