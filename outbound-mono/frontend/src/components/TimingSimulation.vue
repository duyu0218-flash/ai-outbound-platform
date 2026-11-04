<script setup>
import { ref } from 'vue';
import { request } from '../session';
const props = defineProps({ scriptId: [String, Number] });
const sample = ref({ ring: 1, talk: 30, wait_ms: 5000, silences: 2, prior: 0, machine: true }),
  result = ref(null),
  error = ref('');
const fields = [
  ['ring', '模拟振铃秒数'],
  ['talk', '模拟通话秒数'],
  ['wait_ms', '模拟等待毫秒'],
  ['silences', '模拟连续超时次数'],
  ['prior', '窗口内已拨次数'],
];
async function simulate() {
  try {
    result.value = await request(
      '/api/v1/workspace/scripts/' + props.scriptId + '/simulate-timing',
      'POST',
      sample.value,
    );
    error.value = '';
  } catch (e) {
    result.value = null;
    error.value = e.message;
  }
}
</script>
<template>
  <details>
    <summary>已发布参数模拟演练</summary>
    <p>输入合成样本，以当前发布版本计算结果。此操作不创建通话。</p>
    <label v-for="[k, n] in fields" :key="k"
      >{{ n }}<el-input-number :aria-label="n" v-model="sample[k]" :min="0" :precision="0" /></label
    ><label><input type="checkbox" v-model="sample.machine" />识别到语音助手</label
    ><el-button @click="simulate">运行免费演练</el-button
    ><el-alert v-if="error" :title="error" type="error" />
    <p v-if="result" aria-label="参数演练结果">
      准入 {{ result.admitted ? '是' : '否' }} · 接通 {{ result.answered ? '是' : '否' }} · 通话
      {{ result.talk_seconds }} 秒 · 人机协同 {{ result.human ? '是' : '否' }} · 助手拦截
      {{ result.intercepted_machine ? '是' : '否' }} · 等待超时
      {{ result.wait_expired ? '是' : '否' }} · 超时结束 {{ result.silence_ended ? '是' : '否' }} ·
      呼频拦截 {{ result.frequency_block ? '是' : '否' }} · 录音 {{ result.recording_seconds }} 秒
    </p>
  </details>
</template>
