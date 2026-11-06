<script setup>
import { computed, ref } from 'vue';
const policy = defineModel({ required: true });
const props = defineProps({ section: String });
const defaults = () => ({
  voice: '',
  volume: 50,
  speed: 50,
  pitch: 50,
  silence_ms: 800,
  collect_silence_ms: 1600,
  application_id: '',
  recognition_parameters: {},
});
const model = computed(() => policy.value.flow_speech),
  parameters = ref(''),
  error = ref('');
function initialize() {
  policy.value.flow_speech = defaults();
}
function change(value) {
  try {
    model.value.recognition_parameters = JSON.parse(value);
    error.value = '';
  } catch {
    error.value = '参数须为JSON对象';
  }
}
</script>
<template>
  <el-button v-if="!model" @click="initialize">按话术配置语音参数</el-button
  ><el-form v-else label-position="top"
    ><template v-if="section === '语音合成'"
      ><el-form-item label="发音人"
        ><el-input
          v-model="model.voice"
          aria-label="话术发音人"
          placeholder="留空使用服务组合默认值" /></el-form-item
      ><el-form-item
        v-for="[key, label] in [
          ['volume', '音量'],
          ['speed', '速度'],
          ['pitch', '语调'],
        ]"
        :key="key"
        :label="label"
        ><el-slider
          v-model="model[key]"
          :min="0"
          :max="100"
          show-input
          :aria-label="'话术' + label"
      /></el-form-item>
      <p>速度和语调50为标准值。语调参数用于阿里云合成；服务组合决定引擎及可用发音人。</p></template
    ><template v-else
      ><el-form-item label="最大静音等待时长（毫秒）"
        ><el-input-number
          v-model="model.silence_ms"
          :min="200"
          :max="2000"
          aria-label="话术最大静音等待" /></el-form-item
      ><el-form-item label="采集时最大静音时长（毫秒）"
        ><el-input-number
          v-model="model.collect_silence_ms"
          :min="model.silence_ms"
          :max="3000"
          aria-label="话术采集最大静音" /></el-form-item
      ><el-form-item label="应用ID"
        ><el-input v-model="model.application_id" aria-label="语音识别应用ID" /></el-form-item
      ><el-form-item label="识别参数（按供应商的词表JSON）"
        ><el-input
          :model-value="parameters || JSON.stringify(model.recognition_parameters)"
          @update:model-value="
            parameters = $event;
            change($event);
          "
          type="textarea"
          aria-label="语音识别参数" /></el-form-item
      ><el-alert v-if="error" type="error" :title="error" /></template
  ></el-form>
</template>
