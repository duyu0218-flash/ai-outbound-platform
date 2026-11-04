<script setup>
import { ref } from 'vue';
const policy = defineModel({ required: true }),
  emit = defineEmits(['section']),
  seconds = ref(25);
const defaults = {
  human_enabled: true,
  ring_seconds: 50,
  max_seconds: 2600,
  response_ms: 8000,
  timeout_count: 1,
  block_machine: true,
  frequency_hours: 0,
  frequency_count: 1,
  min_seconds: 0,
  record_at: 'answer',
  record_delay: 0,
};
const fields = [
  ['ring_seconds', '呼叫超时时长（秒）', 1, 300],
  ['max_seconds', '最大通话时长（秒）', 1, 3600],
  ['response_ms', '等待响应超时时长（毫秒）', 300, 120000],
  ['timeout_count', '最大连续超时次数', 1, 20],
  ['frequency_hours', '呼频限制窗口（小时）', 0, 720],
  ['frequency_count', '呼频限制次数', 1, 1000],
  ['min_seconds', '最小通话时长（秒）', 0, 3600],
  ['record_delay', '延迟录音时长（秒）', 0, 3600],
];
</script>
<template>
  <el-card header="一般设置"
    ><p>
      发布后由新任务冻结此版本。呼叫和通话超时仍受所选线路上限约束；呼频窗口填 0
      时使用租户的基础限制。
    </p>
    <el-button v-if="!policy.flow_settings" @click="policy.flow_settings = { ...defaults }"
      >配置一般设置</el-button
    ><template v-else>
      <el-form label-position="top"
        ><el-form-item label="是否启用人机协同"
          ><el-switch
            aria-label="是否启用人机协同"
            v-model="policy.flow_settings.human_enabled" /></el-form-item
        ><el-form-item label="拦截语音助手"
          ><el-switch aria-label="拦截语音助手" v-model="policy.flow_settings.block_machine"
        /></el-form-item>
        <el-form-item v-for="[key, label, min, max] in fields" :key="key" :label="label"
          ><el-input-number
            v-model="policy.flow_settings[key]"
            :aria-label="label"
            :min="min"
            :max="max"
            :precision="0"
        /></el-form-item>
        <label
          >录音时机<select aria-label="录音时机" v-model="policy.flow_settings.record_at">
            <option value="answer">接通时</option>
            <option value="disabled">关闭录音</option>
          </select></label
        ></el-form
      >
      <p>
        最小通话时长用于判断时长是否达标。输入样例秒数
        <el-input-number aria-label="示例通话时长" v-model="seconds" :min="0" :max="3600" />
        {{ seconds >= policy.flow_settings.min_seconds ? '达到最小时长' : '未达到最小时长' }}
      </p></template
    >
    <el-button @click="emit('section', 'knowledge')">转接配置</el-button
    ><el-button @click="emit('section', 'slots')">自定义变量</el-button
    ><el-button @click="emit('section', 'hangup')">自定义评级</el-button></el-card
  >
</template>
