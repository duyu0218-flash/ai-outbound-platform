<script setup>
import { ref } from 'vue';
import { request, download } from '../session';
import { numberList } from '../api';
import { useData, action } from '../data';
import { t } from '../i18n';
const page = ref(1),
  keyword = ref(''),
  search = ref(''),
  selected = ref([]),
  upsert = ref(true),
  result = ref(null),
  fileInput = ref(),
  busy = ref(false),
  open = ref(false),
  ids = ref(''),
  batch = ref({ dnc: true, dnc_reason: '' }),
  keys = new Map();
const query = useData(
  () =>
    `/api/v1/contacts?page=${page.value}&size=50${search.value ? '&keyword=' + encodeURIComponent(search.value) : ''}`,
);
async function upload(event) {
  const file = event.target.files?.[0];
  if (!file) return;
  const fingerprint = `${file.name}:${file.size}:${file.lastModified}:${upsert.value}`,
    key = keys.get(fingerprint) || crypto.randomUUID();
  keys.set(fingerprint, key);
  const form = new FormData();
  form.append('file', file);
  busy.value = true;
  try {
    result.value = await action(
      () =>
        request('/api/v1/contacts/import?upsert=' + upsert.value, 'POST', form, {
          headers: { 'Idempotency-Key': key },
          timeoutMs: 600000,
        }),
      { refresh: query.load },
    );
    keys.delete(fingerprint);
  } finally {
    busy.value = false;
    event.target.value = '';
  }
}
function startBatch() {
  ids.value = selected.value.map((row) => row.id).join(',');
  batch.value = { dnc: true, dnc_reason: '' };
  open.value = true;
}
async function saveBatch() {
  const contact_ids = numberList(ids.value);
  if (!contact_ids.length) throw new Error(t('noSelectedContacts'));
  await action(
    () => request('/api/v1/contacts/batch-dnc', 'PATCH', { contact_ids, ...batch.value }),
    { refresh: query.load },
  );
  open.value = false;
  selected.value = [];
  ids.value = '';
}
</script>
<template>
  <PageHeader title="contactsOperations" description="contactsOperationsHint"
    ><el-button :loading="busy" @click="fileInput.click()">{{ t('import') }}</el-button
    ><input ref="fileInput" type="file" accept=".csv,text/csv" hidden @change="upload" /><el-button
      @click="action(() => download('/api/v1/contacts/export', 'contacts-' + Date.now() + '.csv'))"
      >{{ t('export') }}</el-button
    ><el-button @click="result = null">{{ t('clearResult') }}</el-button></PageHeader
  ><el-card
    ><div class="toolbar">
      <el-input
        v-model="keyword"
        aria-label="搜索联系人"
        :placeholder="t('phone') + ' / ' + t('name')"
        @keyup.enter="
          search = keyword;
          page = 1;
        "
      /><el-button
        @click="
          search = keyword;
          page = 1;
        "
        >{{ t('search') }}</el-button
      ><el-button
        @click="
          keyword = '';
          search = '';
          page = 1;
        "
        >{{ t('reset') }}</el-button
      ><span>{{ t('upsert') }}</span
      ><el-switch v-model="upsert" /><el-button
        type="danger"
        :disabled="!selected.length"
        @click="startBatch"
        >{{ t('batchDnc') }}</el-button
      >
    </div>
    <el-alert v-if="query.error.value" type="error" :title="query.error.value" /><DataGrid
      selection
      :rows="query.data.value"
      :columns="[
        { key: 'id', label: 'ID' },
        { key: 'phone', label: 'phone' },
        { key: 'name', label: 'contactName' },
        { key: 'tags', label: 'tags' },
        { key: 'consent_state', label: 'consent', translate: true },
        { key: 'dnc', label: 'dnc' },
        { key: 'dnc_reason', label: 'dncReason' },
      ]"
      :loading="query.loading.value"
      @selection="selected = $event" /><Pager
      :page="page"
      :count="query.data.value?.length || 0"
      @change="page = $event" /></el-card
  ><el-card v-if="result" :header="t('importResult')"
    ><div class="metrics">
      <div v-for="key in ['total', 'created', 'updated', 'skipped', 'errors']" :key="key">
        {{ t(key) }} {{ Array.isArray(result[key]) ? result[key].length : result[key] }}
      </div>
    </div>
    <DataGrid
      v-if="Array.isArray(result.errors)"
      :rows="result.errors"
      :columns="['row', 'phone', 'error']"
      row-key="row" /><JsonView v-else :value="result" /></el-card
  ><el-dialog v-model="open" :title="t('batchDnc')"
    ><el-form-item :label="t('selectedContactIds')"
      ><el-input v-model="ids" type="textarea" /></el-form-item
    ><FieldForm
      :model="batch"
      :fields="[
        { key: 'dnc', label: 'dnc', type: 'boolean' },
        { key: 'dnc_reason', label: 'dncReason', type: 'textarea' },
      ]"
      @submit="saveBatch"
  /></el-dialog>
</template>
