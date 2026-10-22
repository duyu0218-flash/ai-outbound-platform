<script setup>
import { ref } from 'vue';
import { request, download } from '../session';
import { useData, action, modes } from '../data';
import { t } from '../i18n';
import CallTable from '../components/CallTable.vue';
import CallDetails from '../components/CallDetails.vue';
const props = defineProps({ role: String }),
  page = ref(1),
  query = useData(() => `/api/v1/calls?page=${page.value}&size=20`),
  open = ref(false),
  model = ref({}),
  busy = ref(false),
  selected = ref(null);
const fields = [
  {
    key: 'phone',
    label: 'phone',
    required: true,
    pattern: /^\+?[0-9 ()-]{6,32}$/,
    message: t('invalidPhone'),
  },
  { key: 'mode', label: 'mode', required: true, options: modes },
  { key: 'campaign_id', label: 'campaignId', type: 'number' },
  { key: 'contact_id', label: 'contactId', type: 'number' },
  { key: 'max_attempts', label: 'attempts', type: 'number', min: 1, max: 10 },
];
function create() {
  model.value = { mode: 'ai_handoff', max_attempts: 1 };
  open.value = true;
}
async function save() {
  busy.value = true;
  try {
    await action(() => request('/api/v1/calls', 'POST', model.value), { refresh: query.load });
    open.value = false;
  } finally {
    busy.value = false;
  }
}
function command(call, kind) {
  return action(() => request(`/api/v1/calls/${call.id}/${kind}`, 'POST'), {
    confirm: t(
      { handover: 'confirmHandoff', hangup: 'confirmHangup', retry: 'confirmRetry' }[kind],
    ),
    refresh: query.load,
  });
}
</script>
<template>
  <PageHeader title="calls" description="callHint"
    ><el-button
      v-if="role === 'admin'"
      @click="action(() => download('/api/v1/admin/calls/export?days=30'))"
      >{{ t('exportEvidence') }}</el-button
    ><el-button @click="query.load">{{ t('refresh') }}</el-button
    ><el-button type="primary" @click="create">{{ t('startCall') }}</el-button></PageHeader
  ><el-card
    ><el-alert v-if="query.error.value" :title="query.error.value" type="error" /><CallTable
      :rows="query.data.value"
      :loading="query.loading.value"
      @details="selected = $event"
      @command="command" /><Pager
      :page="page"
      :size="20"
      :count="query.data.value?.length || 0"
      :loading="query.loading.value"
      @change="page = $event" /></el-card
  ><el-dialog v-model="open" :title="t('startCall')" destroy-on-close
    ><FieldForm :model="model" :fields="fields" :busy="busy" @submit="save" /></el-dialog
  ><CallDetails v-if="selected" :call="selected" :role="role" @close="selected = null" />
</template>
