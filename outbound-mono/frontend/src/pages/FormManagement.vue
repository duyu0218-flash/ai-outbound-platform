<script setup>
import { ref } from 'vue';
import { useData } from '../data';
import { ops, perform } from '../operations';
import OperationsConfigs from './OperationsConfigs.vue';
const tab = ref('forms'),
  forms = useData('/api/v1/ops/configs?kind=form'),
  profile = useData('/api/v1/ops/me'),
  assignments = useData('/api/v1/ops/benchmark/catalog/forms'),
  form = ref(null),
  users = ref([]);
async function save() {
  const old = assignments.data.value?.find((a) => Number(a.draft.form_id) === Number(form.value));
  await perform(
    () =>
      ops('/benchmark/catalog/forms/form-' + form.value, 'PUT', {
        revision: old?.revision || 0,
        config: { name: '表单分配 ' + form.value, form_id: form.value, user_ids: users.value },
      }),
    assignments.load,
  );
}
function choose() {
  users.value =
    assignments.data.value?.find((a) => Number(a.draft.form_id) === Number(form.value))?.draft
      .user_ids || [];
}
</script>
<template>
  <PageHeader title="表单管理" /><el-tabs v-model="tab"
    ><el-tab-pane name="forms" label="表单列表" /><el-tab-pane
      name="assign"
      label="表单分配" /></el-tabs
  ><OperationsConfigs v-if="tab === 'forms'" form-only /><el-card v-else
    ><el-form label-position="top"
      ><el-form-item label="表单"
        ><el-select v-model="form" aria-label="分配表单" @change="choose"
          ><el-option
            v-for="f in forms.data.value || []"
            :key="f.id"
            :value="f.id"
            :label="f.name + ' v' + f.revision" /></el-select></el-form-item
      ><el-form-item label="可使用账号"
        ><el-select v-model="users" multiple filterable aria-label="表单分配账号"
          ><el-option
            v-for="u in profile.data.value?.users || []"
            :key="u.id"
            :value="u.id"
            :label="u.full_name" /></el-select></el-form-item
      ><el-button type="primary" :disabled="!form" @click="save">保存分配</el-button></el-form
    ></el-card
  >
</template>
