<script setup>
import { ref } from 'vue';
import { request } from '../session';
const props = defineProps({ userId: [Number, String] }),
  emit = defineEmits(['verified']);
const open = ref(false),
  password = ref(''),
  otp = ref(''),
  code = ref(''),
  error = ref(''),
  sent = ref(false),
  busy = ref(false);
async function submit(action) {
  busy.value = true;
  error.value = '';
  try {
    const base = props.userId
      ? '/api/v1/admin/users/' + props.userId + '/phone/'
      : '/api/v1/auth/security/phone/';
    await request(
      base + action,
      'POST',
      action === 'send'
        ? { current_password: password.value, code: otp.value }
        : { code: code.value },
    );
    if (action === 'send') sent.value = true;
    else {
      open.value = false;
      emit('verified');
    }
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <el-button
    link
    @click="
      open = true;
      error = '';
      sent = false;
      code = '';
      password = '';
      otp = '';
    "
    >验证手机号</el-button
  ><el-dialog v-model="open" title="验证手机号" width="480px" destroy-on-close
    ><p>验证码发送到账号已保存的手机号，5分钟有效；发送需要可用的正式短信通道。</p>
    <el-alert v-if="error" :title="error" type="error" /><el-form label-position="top"
      ><el-form-item label="当前操作账号密码"
        ><el-input
          v-model="password"
          type="password"
          aria-label="手机号验证操作密码"
          autocomplete="current-password" /></el-form-item
      ><el-form-item label="双因子验证码（已开启时填写）"
        ><el-input v-model="otp" maxlength="6" aria-label="手机号验证双因子验证码" /></el-form-item
      ><el-button :disabled="busy || !password" @click="submit('send')">发送手机验证码</el-button
      ><el-alert v-if="sent" title="验证码已提交短信通道" type="success" /><el-form-item
        label="手机验证码"
        ><el-input
          v-model="code"
          maxlength="6"
          aria-label="手机验证码"
          autocomplete="one-time-code" /></el-form-item
      ><el-button type="primary" :disabled="busy || code.length !== 6" @click="submit('confirm')"
        >确认验证手机号</el-button
      ></el-form
    ></el-dialog
  >
</template>
