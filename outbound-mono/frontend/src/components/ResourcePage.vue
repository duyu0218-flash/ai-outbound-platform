<script setup>
import { ref, computed } from 'vue';
import { request } from '../session';
import { useData, action } from '../data';
import { t } from '../i18n';
const props = defineProps({
  title: String,
  description: String,
  createLabel: { default: 'create' },
  base: String,
  path: String,
  fields: [Array, Function],
  columns: Array,
  defaults: { type: Object, default: () => ({}) },
  searchable: Boolean,
  deletable: Boolean,
  save: Function,
});
const keyword = ref(''),
  search = ref(''),
  open = ref(false),
  editing = ref(null),
  model = ref({}),
  busy = ref(false),
  query = useData(
    () =>
      props.path +
      (props.searchable && search.value
        ? (props.path.includes('?') ? '&' : '?') + 'keyword=' + encodeURIComponent(search.value)
        : ''),
  ),
  fields = computed(() =>
    typeof props.fields === 'function' ? props.fields(editing.value) : props.fields,
  );
function edit(row) {
  editing.value = row;
  model.value = row
    ? Object.fromEntries(
        fields.value.map((f) => [
          f.key,
          row[f.key] === undefined ? undefined : JSON.parse(JSON.stringify(row[f.key])),
        ]),
      )
    : JSON.parse(JSON.stringify(props.defaults));
  open.value = true;
}
async function save() {
  busy.value = true;
  try {
    await action(
      () =>
        props.save
          ? props.save(model.value, editing.value)
          : request(
              props.base + (editing.value ? '/' + editing.value.id : ''),
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
async function remove(row) {
  await action(() => request(props.base + '/' + row.id, 'DELETE'), {
    confirm: t('confirmDelete'),
    refresh: query.load,
  });
}
defineExpose({ load: query.load, edit });
</script>
<template>
  <PageHeader :title="title" :description="description"
    ><el-button type="primary" @click="edit()">{{ t(createLabel) }}</el-button></PageHeader
  ><el-card
    ><div class="toolbar">
      <template v-if="searchable"
        ><el-input
          v-model="keyword"
          clearable
          :placeholder="t('username') + ' / ' + t('contactName')"
          :aria-label="t('search')"
          @keyup.enter="search = keyword"
        /><el-button type="primary" @click="search = keyword">{{ t('search') }}</el-button
        ><el-button
          @click="
            keyword = '';
            search = '';
          "
          >{{ t('reset') }}</el-button
        ></template
      ><el-button @click="query.load">{{ t('refresh') }}</el-button>
    </div>
    <el-alert v-if="query.error.value" :title="query.error.value" type="error" /><DataGrid
      :pagination="10"
      :rows="query.data.value"
      :columns="columns"
      :loading="query.loading.value"
      ><template v-for="(_, slot) in $slots" #[slot]="scope"
        ><slot :name="slot" v-bind="scope" :reload="query.load" /></template
      ><template #actions="{ row }"
        ><el-button link @click="edit(row)">{{ t('edit') }}</el-button
        ><el-button
          v-if="deletable"
          link
          type="danger"
          :disabled="row.is_active === false"
          @click="remove(row)"
          >{{ t('delete') }}</el-button
        ><slot name="rowActions" :row="row" :reload="query.load" /></template></DataGrid></el-card
  ><el-dialog
    v-model="open"
    :title="t(editing ? 'edit' : createLabel)"
    width="660px"
    destroy-on-close
    ><FieldForm :model="model" :fields="fields" :busy="busy" @submit="save"
  /></el-dialog>
</template>
