<script setup>
import { ref, computed, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { request } from '../session';
import { useData, action, modes } from '../data';
import { t } from '../i18n';
import ContactSelect from '../components/ContactSelect.vue';
const page = ref(1),
  query = useData(() => `/api/v1/campaigns?page=${page.value}&size=50`),
  system = useData('/api/v1/admin/system-overview'),
  templates = useData('/api/v1/script-templates?page=1&size=200'),
  open = ref(false),
  editing = ref(null),
  model = ref({}),
  busy = ref(false),
  clients = useData('/api/v1/ops/clients?status=active&size=200'),
  bindings = useData(() => {
    const ids = (query.data.value || []).map((row) => row.id);
    return ids.length ? '/api/v1/ops/clients/bindings?campaign_ids=' + ids.join(',') : '';
  }),
  clientFilter = ref(''),
  flows = useData(() =>
    open.value && model.value.script_template_id
      ? `/api/v1/script-templates/${model.value.script_template_id}/flows`
      : '',
  );
const bindingOf = computed(() =>
  Object.fromEntries((bindings.data.value || []).map((b) => [b.campaign_id, b])),
);
const visibleRows = computed(() =>
  (query.data.value || [])
    .filter((row) => row.status !== 'deleted')
    .filter((row) => {
      if (!clientFilter.value) return true;
      const bound = bindingOf.value[row.id];
      return clientFilter.value === 'none'
        ? !bound
        : String(bound?.client_id) === String(clientFilter.value);
    }),
);
const capacity = computed(() => system.data.value?.capacity?.effective_max_concurrent_calls);
const fields = computed(() => [
  { key: 'name', label: 'name', required: true },
  {
    key: 'client_id',
    label: 'clientTenantOwner',
    options: [
      { value: '', label: '不绑定（线索不交付给企业）' },
      ...(clients.data.value || []).map((v) => ({ value: v.id, label: `${v.name}（${v.code}）` })),
    ],
    hint: '质检通过的线索将交付到该企业租户门户；改绑只影响之后的交付。',
  },
  {
    key: 'mode',
    label: 'mode',
    required: true,
    options: modes.map((value, i) => ({
      value,
      label: ['aiOnly', 'aiHandoff', 'humanOnly', 'aiWithSms'][i],
    })),
  },
  {
    key: 'voice_ai_pipeline',
    label: 'voiceAiPipeline',
    options: ['inherit', 'legacy', 'pipecat'],
    wide: true,
    hint: 'voiceAiPipelineHint',
  },
  { key: 'contact_ids', label: 'contactsSelected', required: true, wide: true },
  {
    key: 'script_template_id',
    label: 'scriptTemplate',
    options: (templates.data.value || []).map((v) => ({ value: v.id, label: v.name })),
  },
  {
    key: 'script_flow_version_id',
    label: '已发布话术画布',
    disabled: !model.value.script_template_id,
    options: (flows.data.value || [])
      .filter((v) => v.status === 'published')
      .map((v) => ({ value: v.id, label: `${v.name} · v${v.version}` })),
    hint: '任务锁定具体发布版本；后续草稿修改不会影响运行中的外呼。',
  },
  { key: 'script', label: 'content', type: 'textarea', rows: 5 },
  {
    key: 'concurrency',
    label: 'concurrency',
    type: 'number',
    min: 1,
    max: capacity.value,
    disabled: !capacity.value,
  },
  { key: 'retry_limit', label: 'retryLimit', type: 'number', min: 0, max: 10 },
  { key: 'retry_interval_sec', label: 'retryInterval', type: 'number', min: 1 },
  { key: 'attempt_interval_sec', label: 'attemptInterval', type: 'number', min: 1 },
  { key: 'recording_enabled', label: 'recording', type: 'boolean' },
  { key: 'hangup_sms_enabled', label: 'hangupSms', type: 'boolean' },
]);
function edit(row) {
  editing.value = row;
  model.value = row
    ? Object.fromEntries(
        fields.value.map((f) => [
          f.key,
          row[f.key] === undefined ? undefined : JSON.parse(JSON.stringify(row[f.key])),
        ]),
      )
    : {
        mode: 'ai_handoff',
        concurrency: 5,
        retry_limit: 1,
        retry_interval_sec: 30,
        attempt_interval_sec: 1800,
        recording_enabled: true,
        hangup_sms_enabled: true,
        voice_ai_pipeline: 'inherit',
        contact_ids: [],
      };
  model.value.client_id = row ? (bindingOf.value[row.id]?.client_id ?? '') : '';
  open.value = true;
}
async function save() {
  busy.value = true;
  try {
    const { client_id: clientId, ...payload } = model.value;
    for (const key of ['script_template_id', 'script_flow_version_id'])
      if (payload[key] === '') payload[key] = null;
    const saved = await action(
      () =>
        request(
          '/api/v1/campaigns' + (editing.value ? '/' + editing.value.id : ''),
          editing.value ? 'PUT' : 'POST',
          payload,
        ),
      { refresh: query.load },
    );
    const campaignId = saved?.id ?? editing.value?.id,
      before = bindingOf.value[campaignId]?.client_id ?? '';
    if (campaignId && String(before) !== String(clientId ?? '')) {
      try {
        await request(`/api/v1/ops/campaigns/${campaignId}/client`, 'PUT', {
          client_id: clientId === '' ? null : clientId,
        });
      } catch (e) {
        ElMessage.error('任务已保存，但归属企业租户设置失败：' + e.message);
        return;
      } finally {
        await bindings.load();
      }
    }
    open.value = false;
  } finally {
    busy.value = false;
  }
}
function command(row, kind) {
  return action(
    () =>
      request(
        `/api/v1/campaigns/${row.id}/${kind === 'start' ? 'start?async_dial=true' : kind}`,
        'POST',
      ),
    {
      confirm: ['start', 'stop'].includes(kind)
        ? kind === 'start' && !bindingOf.value[row.id]
          ? '该任务未绑定企业租户，产生的线索不会交付给任何企业。确认启动？'
          : t(kind === 'start' ? 'confirmStart' : 'confirmStop')
        : undefined,
      refresh: query.load,
    },
  );
}
</script>
<template>
  <PageHeader title="campaigns" description="campaignHint"
    ><el-button type="primary" @click="edit()">{{ t('createCampaign') }}</el-button></PageHeader
  ><el-card
    ><div class="actions">
      <el-select
        v-model="clientFilter"
        clearable
        placeholder="按归属企业租户筛选"
        style="width: 240px"
        ><el-option label="未绑定" value="none" /><el-option
          v-for="c in clients.data.value || []"
          :key="c.id"
          :label="c.name"
          :value="String(c.id)"
      /></el-select>
    </div>
    <el-alert v-if="query.error.value" :title="query.error.value" type="error" /><DataGrid
      :rows="visibleRows"
      :loading="query.loading.value"
      :columns="[
        { key: 'id', label: 'ID' },
        { key: 'name', label: 'name' },
        {
          key: 'client_tenant',
          label: 'clientTenantOwner',
          render: (value, row) => bindingOf[row.id]?.client_name || '未绑定',
        },
        { key: 'mode', label: 'mode', translate: true },
        { key: 'voice_ai_pipeline', label: 'voiceAiPipeline' },
        { key: 'contact_ids', label: 'contactsSelected', render: (value) => value?.length || 0 },
        { key: 'concurrency', label: 'concurrency' },
        { key: 'status', label: 'status', translate: true, tag: true },
        { key: 'created_at', label: 'createdAt', date: true },
      ]"
      ><template #actions="{ row }"
        ><template v-if="['draft', 'failed', 'stopped', 'prepared'].includes(row.status)"
          ><el-button size="small" type="primary" @click="command(row, 'start')">{{
            t('start')
          }}</el-button
          ><el-button size="small" @click="edit(row)">{{ t('edit') }}</el-button></template
        ><el-button v-if="row.status === 'running'" size="small" @click="command(row, 'pause')">{{
          t('pause')
        }}</el-button
        ><el-button v-if="row.status === 'paused'" size="small" @click="command(row, 'resume')">{{
          t('resume')
        }}</el-button
        ><el-button
          v-if="['running', 'paused', 'prepared'].includes(row.status)"
          size="small"
          type="danger"
          @click="command(row, 'stop')"
          >{{ t('stop') }}</el-button
        ><el-button
          v-else
          size="small"
          type="danger"
          @click="
            action(() => request('/api/v1/campaigns/' + row.id, 'DELETE'), {
              confirm: t('confirmDelete'),
              refresh: query.load,
            })
          "
          >{{ t('delete') }}</el-button
        ></template
      ></DataGrid
    ><Pager :page="page" :count="query.data.value?.length || 0" @change="page = $event" /></el-card
  ><el-dialog
    v-model="open"
    :title="t(editing ? 'editCampaign' : 'createCampaign')"
    width="760px"
    destroy-on-close
    ><FieldForm
      :model="model"
      :fields="fields"
      :busy="busy"
      @change="
        (key) => {
          if (key === 'script_template_id') model.script_flow_version_id = null;
        }
      "
      @submit="save"
      ><template #contact_ids><ContactSelect v-model="model.contact_ids" /></template></FieldForm
  ></el-dialog>
</template>
