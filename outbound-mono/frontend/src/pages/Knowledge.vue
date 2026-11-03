<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessageBox } from 'element-plus';
import { request, session } from '../session';
import { formatDate } from '../api';
import { useData } from '../data';
import KnowledgeDocumentImport from '../components/KnowledgeDocumentImport.vue';
import KnowledgeGapQueue from '../components/KnowledgeGapQueue.vue';
const route = useRoute(),
  router = useRouter(),
  query = useData('/api/v1/workspace/libraries'),
  users = useData('/api/v1/admin/users?page=1&size=200');
const selected = ref(null),
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
  history = ref([]),
  directoryOpen = ref(false),
  directoryMode = ref('create'),
  directoryPath = ref('');
const clone = (v) => JSON.parse(JSON.stringify(v)),
  allowedTabs = ['entries', 'documents', 'gaps', 'settings', 'probe', 'history'];
let disposed = false,
  pending = 0;
const epoch = session.epoch,
  valid = () => !disposed && session.epoch === epoch && route.path === '/admin/knowledge';
const categories = computed(() => selected.value?.categories || []);
const fields = computed(() => [
  {
    key: 'name',
    label: entry.value.kind === 'document' ? '文档分段标题' : '标准问题',
    required: true,
  },
  {
    key: 'category',
    label: '知识分类',
    options: [
      { value: '', label: '未分类' },
      ...categories.value.map((c) => ({ value: c.name, label: c.name })),
    ],
  },
  ...(entry.value.kind === 'document'
    ? []
    : [{ key: 'similar_questions', label: '相似问法', type: 'textarea', rows: 2 }]),
  {
    key: 'content',
    label: entry.value.kind === 'document' ? '文档分段正文' : '标准答案',
    type: 'textarea',
    rows: 8,
    required: true,
  },
  { key: 'keywords', label: '检索关键词', placeholder: '多个关键词用空格分隔' },
  { key: 'source', label: '内容来源' },
  { key: 'valid_from', label: '生效时间（UTC）', placeholder: '2026-11-03T00:00:00' },
  { key: 'valid_until', label: '失效时间（UTC）', placeholder: '2027-01-01T00:00:00' },
  { key: 'enabled', label: '参与发布', type: 'boolean' },
]);
const entries = computed(() =>
  (selected.value?.items || []).filter((v) => {
    const path = v.draft.category || '';
    return (
      (!category.value ||
        (category.value === '__uncategorized__'
          ? !path
          : path === category.value || path.startsWith(category.value + '/'))) &&
      (!keyword.value ||
        [v.draft.name, v.draft.content, v.draft.keywords].join(' ').includes(keyword.value))
    );
  }),
);
async function run(fn) {
  pending++;
  busy.value = true;
  error.value = '';
  try {
    await fn();
  } catch (e) {
    if (valid() && e !== 'cancel' && e !== 'close') error.value = e.message || String(e);
  } finally {
    pending--;
    if (valid()) busy.value = pending > 0;
  }
}
function remember() {
  if (valid() && selected.value)
    router.replace({
      path: '/admin/knowledge',
      query: { ...route.query, library: selected.value.object_id, tab: tab.value },
    });
}
async function loadLibrary(id, preferred) {
  const [lib, versions] = await Promise.all([
    request('/api/v1/workspace/libraries/' + id),
    request(`/api/v1/workspace/libraries/${id}/history`),
  ]);
  if (!valid()) return;
  selected.value = lib;
  model.value = clone(lib.draft);
  history.value = versions;
  const nextTab = preferred ?? tab.value;
  tab.value = allowedTabs.includes(nextTab) ? nextTab : 'entries';
  remember();
}
async function reload() {
  if (selected.value) await loadLibrary(selected.value.object_id);
  await query.load();
}
async function open(lib) {
  await run(async () => {
    category.value = '';
    keyword.value = '';
    hits.value = null;
    await loadLibrary(lib.object_id, 'entries');
  });
}
async function closeLibrary() {
  selected.value = null;
  const q = { ...route.query };
  delete q.library;
  delete q.tab;
  await router.replace({ path: '/admin/knowledge', query: q });
  await query.load();
}
async function create() {
  await run(async () => {
    const lib = await request('/api/v1/workspace/libraries', 'POST', { name: name.value });
    if (!valid()) return;
    createOpen.value = false;
    await query.load();
    await loadLibrary(lib.object_id, 'entries');
  });
}
function edit(row) {
  editing.value = row;
  entry.value = row
    ? clone(row.draft)
    : {
        name: '',
        category: category.value && category.value !== '__uncategorized__' ? category.value : '',
        kind: 'faq',
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
    if (!valid()) return;
    entryOpen.value = false;
    await reload();
  });
}
async function saveMeta() {
  await request(`/api/v1/workspace/libraries/${selected.value.object_id}`, 'PUT', {
    draft: model.value,
    expected_revision: selected.value.revision,
  });
  if (valid()) await reload();
}
async function publish() {
  await run(async () => {
    await saveMeta();
    if (!valid()) return;
    await request(`/api/v1/workspace/libraries/${selected.value.object_id}/publish`, 'POST', {
      expected_revision: selected.value.revision,
    });
    if (valid()) await reload();
  });
}
async function state(value) {
  await run(async () => {
    await request(`/api/v1/workspace/libraries/${selected.value.object_id}/state`, 'POST', {
      state: value,
      expected_revision: selected.value.revision,
    });
    if (valid()) await reload();
  });
}
function directory(mode) {
  directoryMode.value = mode;
  directoryPath.value =
    mode === 'rename'
      ? category.value
      : category.value && category.value !== '__uncategorized__'
        ? category.value + '/'
        : '';
  directoryOpen.value = true;
}
async function saveDirectory() {
  await run(async () => {
    const old = category.value,
      body = {
        expected_revision: selected.value.revision,
        path: directoryMode.value === 'rename' ? old : directoryPath.value,
      };
    if (directoryMode.value === 'rename') body.new_path = directoryPath.value;
    await request(
      `/api/v1/workspace/libraries/${selected.value.object_id}/categories`,
      directoryMode.value === 'rename' ? 'PUT' : 'POST',
      body,
    );
    if (!valid()) return;
    category.value = directoryPath.value;
    directoryOpen.value = false;
    await reload();
  });
}
async function removeDirectory() {
  await run(async () => {
    await ElMessageBox.confirm(
      '删除该分类及子目录？其中知识将移入未分类。已发布版本和任务快照保持不变。',
      '删除分类',
      { confirmButtonText: '删除并移入未分类', cancelButtonText: '取消' },
    );
    await request(
      `/api/v1/workspace/libraries/${selected.value.object_id}/categories/remove`,
      'POST',
      { path: category.value, expected_revision: selected.value.revision },
    );
    if (!valid()) return;
    category.value = '__uncategorized__';
    await reload();
  });
}
watch(tab, remember);
watch(
  () => route.query.tab,
  (value) => {
    if (valid() && selected.value && allowedTabs.includes(value)) tab.value = value;
  },
);
watch(
  () => route.query.library,
  (id) => {
    if (valid() && id && id !== selected.value?.object_id)
      run(() => loadLibrary(id, route.query.tab || 'entries'));
  },
);
onMounted(() => {
  if (route.query.library)
    run(() => loadLibrary(route.query.library, route.query.tab || 'entries'));
});
onBeforeUnmount(() => {
  disposed = true;
});
</script>
<template>
  <PageHeader title="知识库" description="维护可追溯的业务知识，审核发布后关联话术"
    ><el-button
      type="primary"
      :disabled="busy"
      @click="
        name = '';
        createOpen = true;
      "
      >新建知识库</el-button
    ></PageHeader
  >
  <el-alert
    v-if="error || query.error.value"
    type="error"
    :title="error || query.error.value"
    :closable="false"
  />
  <template v-if="!selected">
    <KnowledgeGapQueue
      v-if="route.query.tab === 'gaps'"
      @open-library="(id) => run(() => loadLibrary(id, 'entries'))"
    />

    <el-alert
      v-if="query.data.value?.legacy_count"
      :title="`检测到 ${query.data.value.legacy_count} 条历史知识，可复制到草稿库核对。每次最多迁移500条，原记录保留。`"
      :closable="false"
      ><el-button
        @click="
          run(async () => {
            const lib = await request('/api/v1/workspace/libraries/import-legacy', 'POST');
            if (valid()) {
              await query.load();
              await loadLibrary(lib.object_id, 'entries');
            }
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
        <p>{{ lib.published_version ? '已发布 v' + lib.published_version : '尚未发布' }}</p>
        <p>草稿条目 {{ lib.entry_count || 0 }} 条 · 负责人：{{ lib.owner_name || '未指定' }}</p>
        <small>{{ formatDate(lib.updated_at) }}</small
        ><el-button type="primary" plain @click="open(lib)">管理知识</el-button></el-card
      >
    </div>
    <el-empty
      v-if="!query.loading.value && !query.data.value?.rows?.length"
      description="新建知识库，添加问答或解析文档后发布"
    />
  </template>
  <template v-else>
    <div class="actions">
      <el-button @click="closeLibrary">返回知识库列表</el-button><strong>{{ selected.name }}</strong
      ><el-tag>{{
        selected.published_version > 0 ? `已发布 v${selected.published_version}` : '尚未发布'
      }}</el-tag
      ><el-button :loading="busy" @click="run(reload)">刷新知识库</el-button
      ><el-button :loading="busy" @click="state('review')">提交审核</el-button
      ><el-button type="primary" :loading="busy" @click="publish">发布知识库</el-button
      ><el-button type="danger" plain :disabled="busy" @click="state('disabled')">停用</el-button>
    </div>
    <el-tabs v-model="tab"
      ><el-tab-pane label="知识条目" name="entries" /><el-tab-pane
        label="文档解析与分段"
        name="documents" /><el-tab-pane label="待整理问题" name="gaps" /><el-tab-pane
        label="知识库信息"
        name="settings" /><el-tab-pane label="检索测试" name="probe" /><el-tab-pane
        label="发布历史"
        name="history"
    /></el-tabs>
    <el-card v-if="tab === 'entries'">
      <div class="actions">
        <el-button type="primary" @click="edit()">添加问答</el-button
        ><el-button @click="tab = 'documents'">导入知识文档</el-button
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
          <div class="actions">
            <strong>分类目录</strong
            ><el-button link type="primary" :disabled="busy" @click="directory('create')"
              >新建分类</el-button
            >
          </div>
          <p><el-button link @click="category = ''">全部分类</el-button></p>
          <p>
            <el-button
              link
              :type="category === '__uncategorized__' ? 'primary' : ''"
              @click="category = '__uncategorized__'"
              >未分类</el-button
            >
          </p>
          <p v-for="c in categories" :key="c.name">
            <el-button
              link
              :type="category === c.name ? 'primary' : ''"
              :style="{ paddingLeft: (c.name.split('/').length - 1) * 10 + 'px' }"
              @click="category = c.name"
              >{{ c.name }}（{{ c.entry_count }}）</el-button
            >
          </p>
          <div v-if="category && category !== '__uncategorized__'" class="actions">
            <el-button link :disabled="busy" @click="directory('rename')">重命名分类</el-button
            ><el-button link type="danger" :disabled="busy" @click="removeDirectory"
              >删除分类</el-button
            >
          </div>
          <small>数量包含子分类条目。分类变更只影响草稿，正式检索需重新发布。</small>
        </aside>
        <el-table :data="entries"
          ><el-table-column label="标准问题 / 标题" min-width="190"
            ><template #default="{ row }">{{ row.draft.name }}</template></el-table-column
          ><el-table-column label="类型"
            ><template #default="{ row }">{{
              row.draft.kind === 'document' ? '文档分段' : '问答'
            }}</template></el-table-column
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
          ><el-table-column label="更新时间"
            ><template #default="{ row }">{{
              formatDate(row.updated_at)
            }}</template></el-table-column
          ><el-table-column label="操作"
            ><template #default="{ row }"
              ><el-button link @click="edit(row)">编辑知识</el-button></template
            ></el-table-column
          ></el-table
        >
      </div>
      <p>目录、问答与文档草稿在发布前不会改变已有任务。停用知识条目后需重新发布知识库。</p>
    </el-card>
    <KnowledgeDocumentImport
      v-if="tab === 'documents'"
      :key="selected.object_id"
      :library-id="selected.object_id"
      :library-revision="selected.revision"
      :categories="categories"
      @changed="run(reload)"
    />
    <KnowledgeGapQueue
      v-if="tab === 'gaps'"
      :key="selected.object_id"
      :library-id="selected.object_id"
      :categories="categories"
      @changed="run(reload)"
      @open-library="(id) => open({ object_id: id })"
    />
    <el-card v-if="tab === 'settings'"
      ><FieldForm
        :model="model"
        :fields="[
          { key: 'name', label: '知识库名称', required: true },
          { key: 'description', label: '适用范围与维护说明', type: 'textarea' },
          {
            key: 'owner_id',
            label: '知识库负责人',
            options: (users.data.value || []).map((v) => ({
              value: v.id,
              label: v.full_name || v.username,
            })),
          },
        ]"
        :busy="busy"
        @submit="run(saveMeta)"
    /></el-card>
    <el-card v-if="tab === 'probe'">
      <el-switch
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
            const value = await request(
              `/api/v1/workspace/libraries/${selected.object_id}/probe`,
              'POST',
              { query: sample, draft: draftProbe },
            );
            if (valid()) hits = value;
          })
        "
        >检索测试</el-button
      >
      <p>
        使用关键词和中文片段匹配，展示最多3条结果；排序仅为检索指标，不代表答案正确率。未调用外部模型。
      </p>
      <template v-if="hits"
        ><el-alert :title="hits.scope" :closable="false" /><el-empty
          v-if="!hits.hits.length"
          description="未命中知识，请补充问法或关键词；实际对话应澄清或转人工"
        /><el-card v-for="(hit, index) in hits.hits" :key="hit.id"
          ><strong>{{ index + 1 }}. {{ hit.title }}</strong>
          <p class="knowledge-content">{{ hit.content }}</p>
          <small>来源：{{ hit.source || '人工录入' }} · v{{ hit.version }}</small></el-card
        ></template
      >
    </el-card>
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
                  if (valid()) await reload();
                })
              "
              >恢复为草稿</el-button
            ></template
          ></el-table-column
        ></el-table
      >
      <p>恢复会重建所选版本的分类与启用条目草稿，需重新发布。任务快照保持原版本。</p></el-card
    >
  </template>
  <el-dialog v-model="createOpen" title="新建知识库" width="520px"
    ><el-input v-model="name" aria-label="知识库名称" /><template #footer
      ><el-button type="primary" :loading="busy" @click="create">创建知识库</el-button></template
    ></el-dialog
  >
  <el-dialog
    v-model="entryOpen"
    :title="entry.kind === 'document' ? '编辑文档分段' : '编辑知识问答'"
    width="min(880px,95vw)"
    ><el-alert v-if="error" :title="error" type="error" :closable="false" /><FieldForm
      :model="entry"
      :fields="fields"
      :busy="busy"
      @submit="saveEntry"
  /></el-dialog>
  <el-dialog
    v-model="directoryOpen"
    :title="directoryMode === 'rename' ? '重命名分类' : '新建分类'"
    width="550px"
    ><el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p>使用“产品/办理条件”表示层级，新建时会建立上级目录，最多6级、总长160字符。</p>
    <el-input v-model="directoryPath" aria-label="目录路径" maxlength="160" /><template #footer
      ><el-button type="primary" :loading="busy" @click="saveDirectory"
        >保存分类</el-button
      ></template
    ></el-dialog
  >
</template>
<style scoped>
.knowledge-layout {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  gap: 24px;
  margin-top: 20px;
}
aside {
  border-right: 1px solid #e2e8f0;
  padding-right: 12px;
}
aside p {
  margin: 12px 0;
}
small {
  color: #64748b;
}
.knowledge-content {
  white-space: pre-wrap;
}
@media (max-width: 900px) {
  .knowledge-layout {
    grid-template-columns: 1fr;
  }
  aside {
    border-right: 0;
    border-bottom: 1px solid #e2e8f0;
    padding-bottom: 16px;
  }
}
</style>
