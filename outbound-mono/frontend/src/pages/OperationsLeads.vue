<script setup>
import { ref, reactive, onMounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ops, perform, parse, query, stageOptions, prefix } from '../operations';
import { usePageQuery } from '../page-query';
const { isCurrent, replaceQuery } = usePageQuery();
const route = useRoute(),
  router = useRouter(),
  rows = ref([]),
  total = ref(0),
  busy = ref(false),
  error = ref(''),
  profile = ref({}),
  selected = ref([]),
  owner = ref(''),
  detail = ref(null),
  view = ref('mine'),
  keyword = ref(''),
  stage = ref(''),
  overdue = ref(false),
  page = ref(1),
  work = ref([]),
  forms = ref([]),
  tags = ref([]),
  formId = ref(null),
  values = reactive({}),
  tagValues = ref([]),
  saving = ref(false);
const follow = reactive({ stage: 'in_progress', note: '', next_at: '' }),
  eventKey = ref('');
async function load() {
  if (!isCurrent()) return;
  busy.value = true;
  error.value = '';
  try {
    const data = await ops(
      '/leads?' +
        query({
          view: view.value,
          keyword: keyword.value,
          stage: stage.value,
          overdue: overdue.value,
          page: page.value,
          contact_id: route.query.contact_id,
        }),
    );
    if (!isCurrent()) return;
    rows.value = data.rows;
    total.value = data.total;
    work.value = await ops('/work-items');
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function open(row) {
  try {
    detail.value = await ops('/leads/' + row.contact_id);
    follow.stage = detail.value.stage;
    follow.note = '';
    follow.next_at = detail.value.next_at
      ? new Date(new Date(detail.value.next_at).getTime() - new Date().getTimezoneOffset() * 60000)
          .toISOString()
          .slice(0, 16)
      : '';
    tagValues.value = parse(detail.value.tags_json, []);
    formId.value = detail.value.form_id || null;
    Object.keys(values).forEach((k) => delete values[k]);
    Object.assign(values, parse(detail.value.fields_json));
    eventKey.value = crypto.randomUUID();
  } catch (e) {
    error.value = e.message;
  }
}
function currentFields() {
  const f = forms.value.find((f) => f.id === formId.value);
  return parse(f?.config_json).fields || [];
}
async function change(action, payload = {}) {
  if (!detail.value) return;
  saving.value = true;
  try {
    const result = await perform(() =>
      ops('/leads/' + detail.value.contact_id, 'PATCH', {
        action,
        revision: detail.value.revision,
        event_key: eventKey.value,
        ...payload,
      }),
    );
    if (result) {
      await open(detail.value);
      await load();
    }
  } finally {
    saving.value = false;
  }
}
async function claim(row) {
  saving.value = true;
  try {
    await perform(
      () =>
        ops('/leads/' + row.contact_id, 'PATCH', {
          action: 'claim',
          revision: row.revision,
          event_key: crypto.randomUUID(),
        }),
      load,
    );
  } finally {
    saving.value = false;
  }
}
async function assign() {
  if (!selected.value.length) return;
  await perform(
    () =>
      ops('/leads/batch', 'POST', {
        owner_id: owner.value || null,
        note: '运营工作台批量分配',
        items: selected.value.map((row) => ({
          contact_id: row.contact_id,
          revision: row.revision,
          event_key: crypto.randomUUID(),
        })),
      }),
    load,
  );
}
async function complete(item, state) {
  await perform(() => ops('/work-items/' + item.id, 'PATCH', { state }), load);
}
function reset() {
  keyword.value = '';
  stage.value = '';
  overdue.value = false;
  page.value = 1;
  replaceQuery({});
  load();
}
watch(
  () => route.query,
  () => {
    if (!isCurrent()) return;
    page.value = 1;
    load();
  },
  { deep: true },
);
onMounted(async () => {
  try {
    profile.value = await ops('/me');
    if (!isCurrent()) return;
    forms.value = await ops('/configs?kind=form');
    if (!isCurrent()) return;
    tags.value = await ops('/configs?kind=tag');
  } catch (e) {
    error.value = e.message;
  }
  load();
});
</script>
<template>
  <PageHeader title="线索与跟进" description="领取、分配、记录结果和下次动作"
    ><el-button @click="load">刷新</el-button></PageHeader
  ><el-alert v-if="error" :title="error" type="error" :closable="false" /><el-card
    ><el-radio-group
      v-model="view"
      @change="
        page = 1;
        load();
      "
      ><el-radio-button value="mine">我的线索</el-radio-button
      ><el-radio-button value="pool">公海线索</el-radio-button
      ><el-radio-button v-if="profile.manager" value="all"
        >授权范围</el-radio-button
      ></el-radio-group
    >
    <div class="toolbar">
      <el-input
        v-model="keyword"
        aria-label="搜索线索"
        placeholder="姓名或号码"
        @keyup.enter="
          page = 1;
          load();
        "
      /><el-select v-model="stage" clearable aria-label="筛选跟进状态"
        ><el-option
          v-for="[value, label] in stageOptions"
          :key="value"
          :value="value"
          :label="label" /></el-select
      ><el-checkbox v-model="overdue">只看到期</el-checkbox
      ><el-button
        type="primary"
        @click="
          page = 1;
          load();
        "
        >搜索线索</el-button
      ><el-button @click="reset">重置线索查询</el-button>
    </div>
    <div v-if="profile.manager" class="toolbar">
      <el-select v-model="owner" clearable placeholder="留空回收到公海" aria-label="分配负责人"
        ><el-option
          v-for="u in profile.users || []"
          :key="u.id"
          :value="u.id"
          :label="u.full_name"
          :disabled="!u.enabled" /></el-select
      ><el-button :disabled="!selected.length || saving" @click="assign">批量分配</el-button>
    </div>
    <DataGrid
      selection
      :rows="rows"
      row-key="contact_id"
      :loading="busy"
      :columns="[
        { key: 'name', label: '客户姓名' },
        { key: 'phone', label: '号码' },
        { key: 'owner_name', label: '负责人' },
        {
          key: 'stage',
          label: '跟进状态',
          render: (v) => stageOptions.find((x) => x[0] === v)?.[1] || v,
        },
        { key: 'next_at', label: '下次跟进', date: true },
        { key: 'revision', label: '版本' },
      ]"
      @selection="selected = $event"
      ><template #actions="{ row }"
        ><el-button v-if="!row.owner_id" link :disabled="saving" @click="claim(row)"
          >领取线索</el-button
        ><el-button v-if="row.owner_id || profile.manager" link @click="open(row)"
          >跟进详情</el-button
        ><el-button
          link
          @click="
            router.push({
              path: prefix() + '/operations/calls',
              query: { contact_id: row.contact_id },
            })
          "
          >关联话单</el-button
        ></template
      ></DataGrid
    ><el-pagination
      v-model:current-page="page"
      :page-size="20"
      :total="total"
      layout="prev,pager,next,total"
      @current-change="load"
  /></el-card>
  <el-card header="人工待办"
    ><DataGrid
      :rows="work"
      :pagination="10"
      :columns="[
        { key: 'phone', label: '号码' },
        { key: 'kind', label: '来源' },
        { key: 'state', label: '状态' },
        { key: 'assigned_to', label: '负责人编号' },
        {
          key: 'detail_json',
          label: '内容',
          render: (v) => parse(v).text || parse(v).reason || '-',
        },
      ]"
      ><template #actions="{ row }"
        ><el-select
          v-if="profile.manager"
          :model-value="row.assigned_to"
          aria-label="待办负责人"
          clearable
          @change="
            perform(
              () =>
                ops('/work-items/' + row.id, 'PATCH', {
                  state: row.state,
                  assigned_to: $event || null,
                }),
              load,
            )
          "
          ><el-option
            v-for="u in profile.users || []"
            :key="u.id"
            :value="u.id"
            :label="u.full_name" /></el-select
        ><el-button v-if="row.state !== 'completed'" link @click="complete(row, 'in_progress')"
          >开始处理</el-button
        ><el-button v-if="row.state !== 'completed'" link @click="complete(row, 'completed')"
          >标记完成</el-button
        ></template
      ></DataGrid
    ></el-card
  >
  <el-drawer :model-value="!!detail" title="线索跟进详情" size="680px" @close="detail = null"
    ><template v-if="detail"
      ><p>{{ detail.name }} · {{ detail.phone }} · 当前版本 {{ detail.revision }}</p>
      <el-button @click="open(detail)">刷新详情</el-button
      ><el-tabs
        ><el-tab-pane label="跟进记录"
          ><el-form label-position="top"
            ><el-form-item label="跟进结果"
              ><el-select v-model="follow.stage" aria-label="跟进结果"
                ><el-option
                  v-for="[value, label] in stageOptions"
                  :key="value"
                  :value="value"
                  :label="label" /></el-select></el-form-item
            ><el-form-item label="记录内容"
              ><el-input
                v-model="follow.note"
                type="textarea"
                :rows="4"
                aria-label="跟进记录内容"
                maxlength="2000" /></el-form-item
            ><el-form-item label="下次跟进时间"
              ><el-input
                v-model="follow.next_at"
                type="datetime-local"
                aria-label="下次跟进时间" /></el-form-item
            ><el-button
              type="primary"
              :loading="saving"
              :disabled="!follow.note"
              @click="
                change('follow', {
                  ...follow,
                  next_at: follow.next_at ? new Date(follow.next_at).toISOString() : null,
                })
              "
              >保存跟进</el-button
            ></el-form
          ></el-tab-pane
        ><el-tab-pane label="人工补录"
          ><el-select
            v-model="formId"
            aria-label="选择补录表单"
            @change="Object.keys(values).forEach((k) => delete values[k])"
            ><el-option
              v-for="f in forms"
              :key="f.id"
              :value="f.id"
              :label="f.name + ' v' + f.revision" /></el-select
          ><el-form label-position="top"
            ><el-form-item
              v-for="f in currentFields()"
              :key="f.key"
              :label="f.label"
              :required="f.required"
              ><el-input-number
                v-if="f.type === 'number'"
                v-model="values[f.key]"
                :aria-label="f.label" /><el-switch
                v-else-if="f.type === 'boolean'"
                v-model="values[f.key]"
                :aria-label="f.label" /><el-select
                v-else-if="f.type === 'choice'"
                v-model="values[f.key]"
                :aria-label="f.label"
                ><el-option
                  v-for="choice in f.choices"
                  :key="choice"
                  :value="choice"
                  :label="choice" /></el-select
              ><el-input v-else v-model="values[f.key]" :aria-label="f.label" /></el-form-item
            ><el-button
              :disabled="!formId"
              :loading="saving"
              @click="change('form', { form_id: formId, fields: { ...values } })"
              >保存补录</el-button
            ></el-form
          ></el-tab-pane
        ><el-tab-pane label="客户标签"
          ><el-select v-model="tagValues" multiple aria-label="线索标签"
            ><el-option
              v-for="t in [
                ...new Map(
                  tags
                    .filter((t) => parse(t.config_json).target === 'contact')
                    .map((t) => [t.name, t]),
                ).values(),
              ]"
              :key="t.id"
              :value="t.name"
              :label="t.name" /></el-select
          ><el-button :loading="saving" @click="change('tags', { tags: tagValues })"
            >保存标签</el-button
          ></el-tab-pane
        ><el-tab-pane label="操作历史"
          ><div v-for="h in detail.history" :key="h.id" class="record-card">
            <strong>{{ h.actor }} · {{ h.action }}</strong>
            <p>{{ h.created_at }}</p>
            <p>{{ parse(h.detail_json).note }}</p>
            <JsonView :value="parse(h.detail_json)" />
          </div>
          <el-empty
            v-if="!detail.history?.length"
            description="暂无历史" /></el-tab-pane></el-tabs></template
  ></el-drawer>
</template>
