<script setup>
import { ref, onMounted } from 'vue';
import { ops } from '../operations';
const props = defineProps({ global: Boolean }),
  model = ref(null),
  revision = ref(0),
  error = ref(''),
  busy = ref(false);
const flags = {
  auto_close: '结束自动关闭',
  auto_takeover: '群呼自动接管',
  seamless: '无感接管',
  manual_signin: '手动签入/签出',
  lead_import: '跟进列表导入',
  success_phone: '跟进成功显示号码',
  success_invalid: '成功单/失效单',
  ringtone: '进线提示音',
};
async function load() {
  const v = await ops('/seat-behavior?global=' + props.global);
  model.value = v.config;
  revision.value = v.revision;
}
async function save() {
  busy.value = true;
  error.value = '';
  try {
    await ops('/seat-behavior?global=' + props.global, 'PUT', {
      revision: revision.value,
      config: model.value,
    });
    await load();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function audio(event) {
  const file = event.target.files?.[0];
  if (!file) return;
  if (file.size > 200000) {
    error.value = '提示音不能超过200KB';
    return;
  }
  const bytes = new Uint8Array(await file.arrayBuffer());
  let text = '';
  for (const b of bytes) text += String.fromCharCode(b);
  model.value.ring_audio =
    'data:audio/' + (file.name.endsWith('.wav') ? 'wav' : 'mpeg') + ';base64,' + btoa(text);
  event.target.value = '';
}
async function importLeads(event) {
  const file = event.target.files?.[0];
  if (!file) return;
  const form = new FormData();
  form.append('file', file);
  try {
    const { request } = await import('../session');
    await request('/api/v1/ops/seat-behavior/import-leads', 'POST', form, {
      headers: { 'Idempotency-Key': crypto.randomUUID() },
    });
  } catch (e) {
    error.value = e.message;
  }
  event.target.value = '';
}
onMounted(() => load().catch((e) => (error.value = e.message)));
</script>
<template>
  <el-card :header="global ? '坐席统一行为配置' : '坐席个人行为配置'"
    ><el-alert v-if="error" type="error" :title="error" /><el-table
      v-if="model"
      :data="Object.entries(flags).map(([key, label]) => ({ key, label }))"
      border
      ><el-table-column prop="label" label="配置项" /><el-table-column label="作用域"
        ><template #default="{ row }"
          ><el-radio-group v-if="global" v-model="model.scopes[row.key]"
            ><el-radio value="personal">坐席个人配置</el-radio
            ><el-radio value="uniform">坐席统一配置</el-radio></el-radio-group
          ><span v-else>{{
            model.scopes[row.key] === 'personal' ? '坐席个人配置' : '坐席统一配置'
          }}</span></template
        ></el-table-column
      ><el-table-column label="设置"
        ><template #default="{ row }"
          ><el-switch
            v-model="model[row.key]"
            :disabled="!global && model.scopes[row.key] !== 'personal'"
            :aria-label="row.label"
          /><el-input-number
            v-if="row.key === 'auto_close'"
            v-model="model.close_seconds"
            :disabled="!global && model.scopes.auto_close !== 'personal'"
            :min="0"
            :max="600"
            aria-label="结束自动关闭秒数"
          /><el-input-number
            v-if="row.key === 'auto_takeover'"
            v-model="model.takeover_seconds"
            :disabled="!global && model.scopes.auto_takeover !== 'personal'"
            :min="0"
            :max="60"
            aria-label="群呼自动接管秒数"
          /><template v-if="row.key === 'ringtone'"
            ><input
              type="file"
              accept="audio/wav,audio/mpeg"
              aria-label="上传进线提示音"
              :disabled="!global && model.scopes.ringtone !== 'personal'"
              @change="audio"
            /><el-input-number
              v-model="model.ring_volume"
              :disabled="!global && model.scopes.ringtone !== 'personal'"
              :min="0"
              :max="100"
              aria-label="进线提示音音量"
            /><span>%</span></template
          ></template
        ></el-table-column
      ></el-table
    ><el-button type="primary" :loading="busy" @click="save">保存坐席行为</el-button
    ><label v-if="!global && model?.lead_import"
      >导入跟进列表（xlsx/CSV）<input
        type="file"
        accept=".xlsx,.csv"
        aria-label="坐席跟进列表导入"
        @change="importLeads" /></label
  ></el-card>
</template>
