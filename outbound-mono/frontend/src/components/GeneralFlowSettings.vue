<script setup>
import { session } from '../session';
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
  handoff_hide_dialogue: false,
  handoff_disable_agent_recognition: false,
  handoff_disable_customer_recognition: false,
  frequency_hours: 0,
  frequency_count: 1,
  min_seconds: 0,
  record_at: 'answer',
  record_delay: 0,
  input_gain: 0,
  output_gain: 0,
  noise_gate: false,
  filter_fillers: false,
  fillers: '嗯,呃,啊',
  timeout_prompt: '您好，您还在听吗？',
  intent_threshold: 0,
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
  ['input_gain', '入声音量（dB）', -12, 12],
  ['output_gain', '出声音量（dB）', -12, 12],
  ['intent_threshold', '系统意图阈值（%）', 0, 100],
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
        <el-divider>转接隐私配置</el-divider
        ><el-form-item
          v-for="[key, label] in [
            ['handoff_hide_dialogue', '隐藏对话内容'],
            ['handoff_disable_agent_recognition', '关闭坐席识别'],
            ['handoff_disable_customer_recognition', '关闭用户识别'],
          ]"
          :key="key"
          :label="label"
          ><el-switch v-model="policy.flow_settings[key]" :aria-label="label"
        /></el-form-item>
        <el-form-item v-for="[key, label, min, max] in fields" :key="key" :label="label"
          ><el-input-number
            v-model="policy.flow_settings[key]"
            :aria-label="label"
            :min="min"
            :max="max"
            :precision="0"
        /></el-form-item>
        <el-form-item label="实时降噪（低幅噪声门）"
          ><el-switch
            v-model="policy.flow_settings.noise_gate"
            aria-label="实时降噪" /></el-form-item
        ><el-form-item label="语气词过滤"
          ><el-switch
            v-model="policy.flow_settings.filter_fillers"
            aria-label="语气词过滤" /><el-input
            v-model="policy.flow_settings.fillers"
            aria-label="添加语气词"
            placeholder="逗号分隔" /></el-form-item
        ><el-form-item label="超时播放语音"
          ><el-input v-model="policy.flow_settings.timeout_prompt" aria-label="超时播放语音"
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
    <el-button v-if="session.user?.role === 'admin'" @click="emit('section', 'sms')"
      >短信配置</el-button
    ><el-button @click="emit('section', 'knowledge')">转接配置</el-button
    ><el-button @click="emit('section', 'variables')">自定义变量</el-button
    ><el-button @click="emit('section', 'grades')">自定义评级</el-button
    ><el-button
      v-for="n in ['语音合成', '语音识别', '接口配置', '多开场白设置']"
      :key="n"
      @click="emit('section', n)"
      >{{ n }}</el-button
    ></el-card
  >
</template>
