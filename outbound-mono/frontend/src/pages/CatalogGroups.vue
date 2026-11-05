<script setup>
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import { useData } from '../data';
import { ops, perform, query } from '../operations';
const route = useRoute(),
  kind = computed(() =>
    route.path.includes('filterStrategy')
      ? 'filters'
      : route.path.includes('intent')
        ? 'keywords'
        : 'tags',
  );
const title = computed(
  () => ({ filters: '过滤策略', keywords: '关键词库', tags: '标签管理' })[kind.value],
);
const list = useData(() => '/api/v1/ops/benchmark/catalog/' + kind.value),
  search = ref(''),
  status = ref(''),
  open = ref(false),
  model = ref({}),
  id = ref(''),
  revision = ref(0),
  selected = ref(''),
  tab = ref('scenes'),
  typeFilter = ref('');
const rows = computed(() =>
  (list.data.value || []).filter(
    (r) =>
      (!search.value ||
        (
          r.name +
          ' ' +
          (r.draft.remark || '') +
          ' ' +
          JSON.stringify(r.draft.rules || [])
        ).includes(search.value)) &&
      (!status.value || String(r.draft.enabled) === status.value) &&
      (!typeFilter.value || (r.draft.rules || []).some((rule) => rule.type === typeFilter.value)),
  ),
);
const scripts = useData(() =>
  kind.value === 'keywords' ? '/api/v1/workspace/scripts?size=200' : '',
);
function edit(row) {
  id.value = row?.object_id || crypto.randomUUID();
  revision.value = row?.revision || 0;
  model.value = row
    ? structuredClone(JSON.parse(JSON.stringify(row.draft)))
    : { name: '', enabled: true, remark: '', rules: [], tags: [] };
  open.value = true;
}
async function save() {
  const result = await perform(
    () =>
      ops('/benchmark/catalog/' + kind.value + '/' + id.value, 'PUT', {
        config: model.value,
        revision: revision.value,
      }),
    list.load,
  );
  if (result) open.value = false;
}
async function toggle(row) {
  await perform(
    () =>
      ops('/benchmark/catalog/filters/' + row.object_id, 'PUT', {
        revision: row.revision,
        config: { ...row.draft, enabled: !row.draft.enabled },
      }),
    list.load,
  );
}
async function apply(row) {
  const script = (scripts.data.value?.rows || []).find(
    (s) => String(s.id) === String(selected.value),
  );
  if (!script) return;
  await perform(
    () =>
      ops('/benchmark/keywords/' + row.object_id + '/apply/' + script.id, 'POST', {
        revision: script.revision,
      }),
    scripts.load,
  );
}
</script>
<template>
  <PageHeader
    :title="title"
    :description="
      kind === 'filters' ? '以策略组组合规则，可整体启停并查看拦截效果' : '按组维护并复用业务配置'
    "
    ><el-button type="primary" @click="edit()">{{
      kind === 'filters' ? '新建策略组' : kind === 'keywords' ? '添加场景' : '添加标签组'
    }}</el-button></PageHeader
  >
  <el-alert v-if="list.error.value" :title="list.error.value" type="error" />
  <el-card
    ><div class="toolbar">
      <el-input v-model="search" placeholder="按名称或备注搜索" aria-label="配置搜索" /><el-select
        v-if="kind === 'filters'"
        v-model="status"
        clearable
        aria-label="策略状态"
        ><el-option label="生效" value="true" /><el-option label="停用" value="false" /></el-select
      ><el-button @click="list.load">刷新</el-button
      ><el-button v-if="kind === 'filters'" @click="$router.push('/admin/dashBoard/intercept')"
        >拦截统计</el-button
      >
    </div>
    <el-tabs v-if="kind === 'keywords'" v-model="tab"
      ><el-tab-pane name="scenes" label="场景管理" /><el-tab-pane
        name="keywords"
        label="关键词管理"
    /></el-tabs>
    <el-table
      v-if="kind === 'keywords' && tab === 'keywords'"
      :data="rows.flatMap((scene) => (scene.draft.rules || []).map((rule) => ({ ...rule, scene })))"
      border
      ><el-table-column prop="scene.name" label="场景" /><el-table-column
        prop="name"
        label="意图"
      /><el-table-column prop="keywords" label="关键词" /><el-table-column
        prop="regex"
        label="正则"
      /><el-table-column prop="target" label="识别结果" /><el-table-column label="操作"
        ><template #default="{ row }"
          ><el-button link @click="edit(row.scene)">编辑关键词</el-button></template
        ></el-table-column
      ></el-table
    ><el-table v-else :data="rows" border
      ><el-table-column prop="object_id" label="ID" width="160" /><el-table-column
        prop="name"
        label="名称"
      /><el-table-column label="规则数"
        ><template #default="{ row }">{{
          row.draft.rules?.length ?? row.draft.tags?.length ?? 0
        }}</template></el-table-column
      ><el-table-column label="内容"
        ><template #default="{ row }">{{
          (row.draft.rules || []).map((r) => r.keywords || r.value).join('；') ||
          (row.draft.tags || []).join('、')
        }}</template></el-table-column
      ><el-table-column prop="updated_at" label="更新时间" /><el-table-column
        v-if="kind === 'filters'"
        label="是否生效"
        ><template #default="{ row }"
          ><el-switch
            :model-value="row.draft.enabled"
            :aria-label="row.name + '生效'"
            @change="toggle(row)" /></template></el-table-column
      ><el-table-column label="操作" width="240"
        ><template #default="{ row }"
          ><el-button link @click="edit(row)">编辑</el-button
          ><template v-if="kind === 'keywords'"
            ><el-select v-model="selected" placeholder="目标话术" aria-label="关键词目标话术"
              ><el-option
                v-for="s in scripts.data.value?.rows || []"
                :key="s.id"
                :value="s.id"
                :label="s.name" /></el-select
            ><el-button link :disabled="!selected" @click="apply(row)"
              >采用到话术草稿</el-button
            ></template
          ></template
        ></el-table-column
      ></el-table
    >
  </el-card>
  <el-dialog v-model="open" :title="title" width="800px"
    ><el-form label-position="top"
      ><el-form-item label="名称" required
        ><el-input v-model="model.name" aria-label="配置名称" maxlength="160"
      /></el-form-item>
      <template v-if="kind === 'filters'"
        ><el-form-item label="备注"
          ><el-input v-model="model.remark" aria-label="策略备注" /></el-form-item
        ><el-form-item label="生效"><el-switch v-model="model.enabled" /></el-form-item>
        <div v-for="(r, i) in model.rules" :key="i" class="toolbar">
          <el-select v-model="r.type" aria-label="过滤规则类型"
            ><el-option
              v-for="[v, l] in [
                ['phone', '号码黑名单'],
                ['prefix', '号码前缀'],
                ['city', '已采集城市'],
                ['frequency', '每日频次上限'],
              ]"
              :key="v"
              :value="v"
              :label="l" /></el-select
          ><el-input v-model="r.value" aria-label="过滤规则值" /><el-button
            @click="model.rules.splice(i, 1)"
            >移除</el-button
          >
        </div>
        <el-button @click="model.rules.push({ type: 'phone', value: '' })"
          >添加规则</el-button
        ></template
      >
      <template v-if="kind === 'keywords'"
        ><div v-for="(r, i) in model.rules" :key="i">
          <el-divider>意图 {{ i + 1 }}</el-divider
          ><el-input v-model="r.name" placeholder="意图名称" aria-label="意图名称" /><el-select
            v-model="r.target"
            aria-label="意图结果"
            ><el-option
              v-for="[v, l] in [
                ['affirm', '肯定'],
                ['deny', '否定'],
                ['rejected', '拒绝'],
                ['continue', '继续'],
                ['wait', '在忙'],
                ['machine_candidate', '语音助手'],
                ['interested', '感兴趣'],
                ['question', '问题'],
              ]"
              :key="v"
              :value="v"
              :label="l" /></el-select
          ><el-input
            v-model="r.keywords"
            placeholder="关键词，逗号分隔"
            aria-label="意图关键词"
          /><el-input v-model="r.regex" placeholder="正则，可选" aria-label="意图正则" /><el-button
            @click="model.rules.splice(i, 1)"
            >移除意图</el-button
          >
        </div>
        <el-button
          @click="model.rules.push({ name: '', target: 'interested', keywords: '', regex: '' })"
          >添加意图</el-button
        ></template
      >
      <el-form-item v-if="kind === 'tags'" label="组内标签"
        ><el-select
          v-model="model.tags"
          multiple
          filterable
          allow-create
          default-first-option
          aria-label="组内标签"
      /></el-form-item>
      <el-button type="primary" :disabled="!model.name?.trim()" @click="save"
        >保存配置</el-button
      ></el-form
    ></el-dialog
  >
</template>
