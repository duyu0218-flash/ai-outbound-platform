<script setup>
import { ref } from 'vue';
import { request } from '../session';
import { action } from '../data';
import { t } from '../i18n';
const props = defineProps({ analysis: Object, callId: String }),
  emit = defineEmits(['close', 'saved']),
  busy = ref(false);
let flags = props.analysis.qa_flags_json;
try {
  flags = JSON.parse(flags).join(', ');
} catch {}
const model = ref({
  result_code: props.analysis.result_code,
  intent: props.analysis.intent,
  sentiment: props.analysis.sentiment,
  qa_score: props.analysis.qa_score,
  qa_flags: flags,
  summary: props.analysis.summary,
});
const fields = [
  {
    key: 'result_code',
    label: 'callResult',
    required: true,
    options: [
      'interested',
      'qualified_lead',
      'rejected',
      'completed',
      'no_answer',
      'busy',
      'failed',
    ],
  },
  { key: 'intent', label: 'intent', required: true },
  {
    key: 'sentiment',
    label: 'sentiment',
    required: true,
    options: ['positive', 'neutral', 'negative'],
  },
  { key: 'qa_score', label: 'qaScore', required: true, type: 'number', min: 0, max: 100 },
  { key: 'qa_flags', label: 'qaFlags', hint: 'qaFlagsHint', wide: true },
  { key: 'summary', label: 'summary', required: true, type: 'textarea', rows: 5 },
];
async function save() {
  busy.value = true;
  try {
    await action(
      () =>
        request('/api/v1/calls/' + props.callId + '/analysis', 'PUT', {
          ...model.value,
          qa_flags: model.value.qa_flags
            .split(/[,，]/)
            .map((v) => v.trim())
            .filter(Boolean),
        }),
      { success: t('reviewSaved') },
    );
    emit('saved');
    emit('close');
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <el-dialog
    :model-value="true"
    :title="t('reviewCall')"
    width="640px"
    @close="emit('close')"
    destroy-on-close
    ><FieldForm :model="model" :fields="fields" :busy="busy" @submit="save"
  /></el-dialog>
</template>
