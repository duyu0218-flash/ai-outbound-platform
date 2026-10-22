<script setup>
import { ref } from 'vue';
import { request } from '../session';
import { useData, action } from '../data';
import { t } from '../i18n';
import { formatDate } from '../api';
import FlowDesigner from '../components/FlowDesigner.vue';
const page = ref(1),
  query = useData(() => `/api/v1/script-templates?page=${page.value}&size=50`),
  open = ref(false),
  editing = ref(null),
  model = ref({}),
  busy = ref(false),
  flow = ref(null);
const fields = [
  { key: 'name', label: 'name', required: true },
  { key: 'category', label: 'category', required: true },
  { key: 'content', label: 'content', type: 'textarea', rows: 8, required: true },
  { key: 'description', label: 'description', type: 'textarea', rows: 2 },
  { key: 'tags', label: 'tags' },
  { key: 'is_active', label: 'enabled', type: 'boolean' },
];
function edit(row) {
  editing.value = row;
  model.value = row
    ? Object.fromEntries(fields.map((f) => [f.key, row[f.key]]))
    : { category: 'default', is_active: true };
  open.value = true;
}
async function save() {
  busy.value = true;
  try {
    await action(
      () =>
        request(
          '/api/v1/script-templates' + (editing.value ? '/' + editing.value.id : ''),
          editing.value ? 'PUT' : 'POST',
          model.value,
        ),
      { refresh: query.load },
    );
    open.value = false;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <PageHeader title="scripts" description="scriptHint"
    ><el-button type="primary" @click="edit()">{{ t('createScript') }}</el-button></PageHeader
  ><el-alert v-if="query.error.value" type="error" :title="query.error.value" />
  <div v-loading="query.loading.value" class="script-grid">
    <el-card v-for="item in query.data.value || []" :key="item.id"
      ><template #header
        ><div class="actions">
          <strong>{{ item.name }}</strong
          ><el-switch
            :model-value="item.is_active"
            :aria-label="item.name + '启用'"
            @change="
              action(
                () =>
                  request('/api/v1/script-templates/' + item.id, 'PUT', {
                    is_active: !item.is_active,
                  }),
                { refresh: query.load },
              )
            "
          /></div
      ></template>
      <div class="actions">
        <el-tag>{{ item.category }}</el-tag
        ><el-tag>v{{ item.version }}</el-tag
        ><el-tag v-if="item.tags">{{ item.tags }}</el-tag>
      </div>
      <p class="script-content">{{ item.content }}</p>
      <small>{{ formatDate(item.updated_at) }}</small
      ><template #footer
        ><div class="actions">
          <el-button link @click="flow = item">话术画布</el-button
          ><el-button link @click="edit(item)">{{ t('edit') }}</el-button
          ><el-button
            link
            type="danger"
            @click="
              action(() => request('/api/v1/script-templates/' + item.id, 'DELETE'), {
                confirm: t('confirmDelete'),
                refresh: query.load,
              })
            "
            >{{ t('delete') }}</el-button
          >
        </div></template
      ></el-card
    >
  </div>
  <el-card v-if="!query.loading.value && !query.data.value?.length"
    ><el-empty :description="t('empty')" /></el-card
  ><Pager :page="page" :count="query.data.value?.length || 0" @change="page = $event" /><el-dialog
    v-model="open"
    :title="t(editing ? 'edit' : 'createScript')"
    width="680px"
    destroy-on-close
    ><FieldForm :model="model" :fields="fields" :busy="busy" @submit="save" /></el-dialog
  ><FlowDesigner v-if="flow" :template="flow" @close="flow = null" />
</template>
