<script setup>
import { ref, reactive, onMounted } from 'vue';
import { ops, perform, parse } from '../operations';
import SettingSection from '../components/SettingSection.vue';
const objects = ref([]),
  objectId = ref(''),
  objectTags = ref([]),
  objectType = ref('call');
const forms = ref([]),
  tags = ref([]),
  deliveries = ref([]),
  error = ref(''),
  open = ref(false),
  model = reactive({ name: '', fields: [], expected_revision: 0 }),
  tag = reactive({ name: '', target: 'contact' }),
  tab = ref('form');
async function load() {
  try {
    forms.value = await ops('/configs?kind=form');
    tags.value = await ops('/configs?kind=tag');
    deliveries.value = await ops('/deliveries');
  } catch (e) {
    error.value = e.message;
  }
}
function edit(row) {
  Object.assign(
    model,
    row
      ? { name: row.name, fields: parse(row.config_json).fields, expected_revision: row.revision }
      : {
          name: '',
          fields: [{ key: 'result', label: '跟进结果', type: 'text', required: true, choices: [] }],
          expected_revision: 0,
        },
  );
  open.value = true;
}
async function save() {
  const result = await perform(
    () =>
      ops('/configs', 'POST', {
        kind: 'form',
        name: model.name,
        expected_revision: model.expected_revision,
        config: {
          fields: model.fields.map((f) => ({
            ...f,
            choices:
              typeof f.choices === 'string'
                ? f.choices
                    .split('、')
                    .map((v) => v.trim())
                    .filter(Boolean)
                : f.choices,
          })),
        },
      }),
    load,
  );
  if (result) open.value = false;
}
async function loadObjects() {
  try {
    objects.value = await ops('/tag-objects?target=' + objectType.value);
    objectId.value = '';
    objectTags.value = [];
  } catch (e) {
    error.value = e.message;
  }
}
async function loadTags() {
  if (!objectId.value) return;
  objectTags.value = (
    await ops(
      '/tags?' + new URLSearchParams({ target: objectType.value, object_id: objectId.value }),
    )
  ).map((v) => v.tag);
}
async function addTag() {
  const previous = tags.value.find((t) => t.name === tag.name);
  await perform(
    () =>
      ops('/configs', 'POST', {
        kind: 'tag',
        name: tag.name,
        expected_revision: previous?.revision || 0,
        config: { target: tag.target },
      }),
    load,
  );
}
onMounted(async () => {
  await load();
  await loadObjects();
});
</script>
<template>
  <PageHeader title="表单标签与业务连接" /><el-alert
    v-if="error"
    :title="error"
    type="error"
  /><el-tabs v-model="tab"
    ><el-tab-pane name="form" label="表单模板"
      ><el-card
        ><el-button type="primary" @click="edit()">新增表单模板</el-button
        ><DataGrid
          :rows="forms"
          :pagination="10"
          :columns="[
            { key: 'name', label: '模板' },
            { key: 'revision', label: '版本' },
            { key: 'created_at', label: '发布时间', date: true },
            {
              key: 'config_json',
              label: '字段',
              render: (v) => (parse(v).fields || []).map((f) => f.label).join('、'),
            },
          ]"
          ><template #actions="{ row }"
            ><el-button link @click="edit(row)">基于此版编辑</el-button></template
          ></DataGrid
        >
        <p>发布保留旧版；人工补录记录模板编号和版本。表单数据与AI采集结果分别保留来源。</p></el-card
      ></el-tab-pane
    ><el-tab-pane name="tag" label="标签字典"
      ><el-card
        ><div class="toolbar">
          <el-input v-model="tag.name" placeholder="标签名称" aria-label="标签名称" /><el-select
            v-model="tag.target"
            aria-label="标签对象"
            ><el-option
              v-for="[value, label] in [
                ['contact', '客户'],
                ['call', '通话'],
                ['campaign', '任务'],
                ['script', '话术'],
              ]"
              :key="value"
              :label="label"
              :value="value" /></el-select
          ><el-button :disabled="!tag.name" @click="addTag">发布标签</el-button>
        </div>
        <DataGrid
          :rows="tags"
          :pagination="10"
          :columns="[
            { key: 'name', label: '名称' },
            { key: 'revision', label: '版本' },
            { key: 'config_json', label: '作用对象', render: (v) => parse(v).target },
          ]" /></el-card
      ><el-card header="给业务对象设置标签"
        ><div class="toolbar">
          <el-select v-model="objectType" aria-label="标签应用类型" @change="loadObjects"
            ><el-option
              v-for="[value, label] in [
                ['call', '通话'],
                ['campaign', '任务'],
                ['script', '话术'],
              ]"
              :key="value"
              :label="label"
              :value="value" /></el-select
          ><el-select v-model="objectId" filterable aria-label="标签业务对象" @change="loadTags"
            ><el-option v-for="v in objects" :key="v.id" :label="v.name" :value="v.id" /></el-select
          ><el-select v-model="objectTags" multiple aria-label="对象标签"
            ><el-option
              v-for="t in [
                ...new Map(
                  tags
                    .filter((t) => parse(t.config_json).target === objectType)
                    .map((t) => [t.name, t]),
                ).values(),
              ]"
              :key="t.id"
              :value="t.name"
              :label="t.name" /></el-select
          ><el-button
            :disabled="!objectId"
            @click="
              perform(
                () =>
                  ops('/tags', 'POST', {
                    target: objectType,
                    object_id: String(objectId),
                    tags: objectTags,
                  }),
                loadTags,
              )
            "
            >保存对象标签</el-button
          >
        </div>
        <p>客户标签在线索详情维护；通话、任务和话术标签可用于话单组合查询。</p></el-card
      ></el-tab-pane
    ><el-tab-pane name="connection" label="业务连接"
      ><el-card header="业务回调配置"><SettingSection section="integration" /></el-card
      ><el-card header="业务回执与失败重试"
        ><p>
          使用现有签名业务回调及持久队列。外部系统授权、实际送达和企微原生对接需要对应服务凭证与联调。
        </p>
        <DataGrid
          :rows="deliveries"
          :pagination="10"
          :columns="[
            { key: 'call_session_id', label: '关联通话' },
            { key: 'state', label: '交付状态' },
            { key: 'attempts', label: '尝试' },
            { key: 'last_error', label: '失败原因' },
            { key: 'available_at', label: '下次执行', date: true },
          ]"
          ><template #actions="{ row }"
            ><el-button
              link
              :disabled="row.state.toLowerCase() !== 'failed'"
              @click="perform(() => ops('/deliveries/' + row.id + '/retry', 'POST'), load)"
              >重试失败回调</el-button
            ></template
          ></DataGrid
        ><el-button @click="load">刷新回执</el-button></el-card
      ></el-tab-pane
    ></el-tabs
  ><el-dialog v-model="open" title="表单模板编辑" width="800px"
    ><el-form label-position="top"
      ><el-form-item label="模板名称"
        ><el-input v-model="model.name" aria-label="表单模板名称" /></el-form-item
      ><el-card v-for="(f, index) in model.fields" :key="index"
        ><div class="form-grid">
          <el-form-item label="字段标识"
            ><el-input
              v-model="f.key"
              :aria-label="'字段标识' + index"
              placeholder="小写英文或下划线" /></el-form-item
          ><el-form-item label="显示名称"
            ><el-input v-model="f.label" :aria-label="'字段名称' + index" /></el-form-item
          ><el-form-item label="类型"
            ><el-select v-model="f.type" :aria-label="'字段类型' + index"
              ><el-option
                v-for="[value, label] in [
                  ['text', '文本'],
                  ['number', '数字'],
                  ['choice', '单选'],
                  ['boolean', '是或否'],
                ]"
                :key="value"
                :value="value"
                :label="label" /></el-select></el-form-item
          ><el-form-item label="必填"
            ><el-switch v-model="f.required" :aria-label="'字段必填' + index" /></el-form-item
          ><el-form-item v-if="f.type === 'choice'" label="选项 用顿号分隔"
            ><el-input
              :model-value="Array.isArray(f.choices) ? f.choices.join('、') : f.choices"
              :aria-label="'字段选项' + index"
              @update:model-value="f.choices = $event"
          /></el-form-item>
        </div>
        <el-button type="danger" @click="model.fields.splice(index, 1)"
          >删除字段</el-button
        ></el-card
      ><el-button
        @click="
          model.fields.push({ key: '', label: '', type: 'text', required: false, choices: [] })
        "
        >添加字段</el-button
      ><el-button type="primary" :disabled="!model.name || !model.fields.length" @click="save"
        >发布表单模板</el-button
      ></el-form
    ></el-dialog
  >
</template>
