<script setup>
import { ref } from 'vue';
import { session, request, clearSession } from '../session';
const username = ref(session.user?.username || ''),
  password = ref(''),
  code = ref(''),
  error = ref(''),
  busy = ref(false);
async function save() {
  busy.value = true;
  try {
    const v = await request('/api/v1/auth/profile', 'PUT', {
      username: username.value,
      current_password: password.value,
      code: code.value,
    });
    if (v.login_required) {
      clearSession();
      location.assign('/admin/login');
    }
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <el-alert v-if="error" type="error" :title="error" /><el-form
    label-position="top"
    style="max-width: 440px"
    ><el-form-item label="登录名"
      ><el-input v-model="username" aria-label="个人登录名" /></el-form-item
    ><el-form-item label="当前密码"
      ><el-input
        v-model="password"
        type="password"
        show-password
        aria-label="修改登录名当前密码" /></el-form-item
    ><el-form-item label="双因子验证码（已开启时填写）"
      ><el-input v-model="code" aria-label="修改登录名验证码" maxlength="6" /></el-form-item
    ><el-button type="primary" :loading="busy" @click="save">修改登录名</el-button></el-form
  >
</template>
