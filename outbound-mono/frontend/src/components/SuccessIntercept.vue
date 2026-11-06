<script setup>
import { ref, onMounted } from 'vue';
import { ops } from '../operations';
const conf = ref(null),
  revision = ref(0),
  error = ref('');
async function load() {
  const v = await ops('/benchmark/task-settings');
  conf.value = v.draft;
  revision.value = v.revision;
}
async function save() {
  try {
    await ops('/benchmark/task-settings', 'PUT', { revision: revision.value, config: conf.value });
    await load();
  } catch (e) {
    error.value = e.message;
  }
}
onMounted(() => load().catch((e) => (error.value = e.message)));
</script>
<template>
  <el-alert v-if="error" type="error" :title="error" /><el-form-item v-if="conf" label="成功单拦截"
    ><el-switch v-model="conf.block_successful" aria-label="成功单拦截" /><el-button @click="save"
      >修改成功单拦截</el-button
    >
    <p>启用后，已标记跟进成功的客户不再发起新呼叫。</p></el-form-item
  >
</template>
