<script setup>
import { ref, onMounted } from 'vue';
import { ops } from '../operations';
const props = defineProps({ type: String }),
  model = ref(null),
  revision = ref(0),
  key = ref(''),
  params = ref('{}'),
  error = ref(''),
  busy = ref(false);
async function load() {
  const v = await ops('/benchmark/push/' + props.type);
  model.value = v.config;
  revision.value = v.revision;
  params.value = JSON.stringify(v.config.parameters, null, 2);
}
async function save() {
  busy.value = true;
  error.value = '';
  try {
    model.value.parameters = JSON.parse(params.value);
    await ops('/benchmark/push/' + props.type, 'PUT', {
      revision: revision.value,
      config: model.value,
      secret_key: key.value,
    });
    key.value = '';
    await load();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function restore() {
  model.value = await ops('/benchmark/push/' + props.type + '/defaults');
  key.value = '';
  params.value = '{}';
}
onMounted(() => load().catch((e) => (error.value = e.message)));
</script>
<template>
  <el-alert v-if="error" type="error" :title="error" /><el-form
    v-if="model"
    label-position="top"
    style="max-width: 650px"
    ><el-form-item label="启用推送"
      ><el-switch v-model="model.enabled" :aria-label="type + '启用推送'" /></el-form-item
    ><el-form-item label="推送模式"
      ><el-radio-group v-model="model.mode"
        ><el-radio value="normal">一般推送</el-radio
        ><el-radio value="advanced">高级推送</el-radio></el-radio-group
      ></el-form-item
    ><el-form-item label="推送地址"
      ><el-input
        v-model="model.url"
        :aria-label="type + '推送地址'"
        placeholder="https://" /></el-form-item
    ><el-form-item label="解密密钥"
      ><el-input
        v-model="key"
        type="password"
        show-password
        :aria-label="type + '解密密钥'"
        :placeholder="model.key_configured ? '已配置，留空保留' : '32至256个字符'"
        autocomplete="new-password" /></el-form-item
    ><el-form-item label="解密算法"
      ><el-select v-model="model.algorithm" :aria-label="type + '解密算法'"
        ><el-option value="hmac_sha256" label="HMAC-SHA256签名" /><el-option
          value="aes_gcm"
          label="AES-256-GCM加密与签名" /></el-select></el-form-item
    ><el-form-item label="扩展参数（JSON对象）"
      ><el-input v-model="params" type="textarea" :aria-label="type + '扩展参数'" /></el-form-item
    ><el-form-item label="话单类型"
      ><el-radio-group v-model="model.call_type"
        ><el-radio value="all">全部</el-radio
        ><el-radio value="answered">已接通</el-radio></el-radio-group
      ></el-form-item
    ><el-form-item v-if="type === 'balance'" label="欠费提醒阈值（元）"
      ><el-input-number
        v-model="model.threshold"
        :min="0"
        :max="10000000"
        aria-label="推送欠费阈值"
    /></el-form-item>
    <p>失败通过后台队列重试；相同交付编号用于接收方去重。</p>
    <el-button @click="restore">恢复推送配置</el-button
    ><el-button type="primary" :loading="busy" @click="save">保存推送配置</el-button></el-form
  >
</template>
