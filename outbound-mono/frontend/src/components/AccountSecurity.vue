<script setup>
import { ref, onMounted } from 'vue';
import PhoneVerification from './PhoneVerification.vue';
import { request } from '../session';
const model = ref(null),
  revision = ref(0),
  ips = ref(''),
  password = ref(''),
  code = ref(''),
  enrollment = ref(null),
  key = ref(''),
  error = ref(''),
  busy = ref(false);
async function load() {
  const v = await request('/api/v1/auth/security');
  model.value = v.config;
  revision.value = v.revision;
  ips.value = v.config.ips.join('\n');
}
async function run(fn) {
  busy.value = true;
  error.value = '';
  try {
    await fn();
    await load();
    code.value = '';
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
const credentials = () => ({ current_password: password.value, code: code.value });
function save() {
  return run(() =>
    request('/api/v1/auth/security', 'PUT', {
      ...credentials(),
      revision: revision.value,
      config: {
        ...model.value,
        ips: ips.value
          .split('\n')
          .map((v) => v.trim())
          .filter(Boolean),
      },
    }),
  );
}
function enroll() {
  return run(
    async () =>
      (enrollment.value = await request('/api/v1/auth/security/totp', 'POST', credentials())),
  );
}
function confirm() {
  return run(async () => {
    await request('/api/v1/auth/security/totp/confirm', 'POST', { code: code.value });
    enrollment.value = null;
  });
}
function createKey() {
  return run(
    async () =>
      (key.value = (await request('/api/v1/auth/security/key', 'POST', credentials())).api_key),
  );
}
onMounted(() => run(async () => {}));
</script>
<template>
  <el-alert v-if="error" type="error" :title="error" /><el-form
    v-if="model"
    label-position="top"
    style="max-width: 640px"
  >
    <el-form-item label="当前密码（修改安全设置时验证）"
      ><el-input
        v-model="password"
        type="password"
        show-password
        aria-label="安全设置当前密码"
        autocomplete="current-password"
    /></el-form-item>
    <el-form-item label="双因子验证码"
      ><el-input
        v-model="code"
        maxlength="6"
        aria-label="安全设置双因子验证码"
        autocomplete="one-time-code"
    /></el-form-item>
    <el-form-item label="双因子验证"
      ><el-tag>{{ model.mfa_enabled ? '已开启' : '未开启' }}</el-tag
      ><el-button :disabled="busy || !password" @click="enroll">{{
        model.mfa_enabled ? '重新配置验证器' : '开启双因子验证'
      }}</el-button
      ><el-button
        v-if="model.mfa_enabled"
        :disabled="busy || !password || code.length !== 6"
        @click="run(() => request('/api/v1/auth/security/totp/disable', 'POST', credentials()))"
        >关闭双因子验证</el-button
      ></el-form-item
    >
    <el-card v-if="enrollment" header="绑定验证器"
      ><p>在验证器中手动输入密钥，再输入生成的六位验证码确认。</p>
      <code>{{ enrollment.secret }}</code>
      <p><a :href="enrollment.uri">在验证器中打开</a></p>
      <el-button :disabled="busy || code.length !== 6" @click="confirm"
        >确认双因子验证</el-button
      ></el-card
    >
    <el-form-item label="导出验证码"
      ><el-switch
        v-model="model.export_verification"
        :disabled="!model.mfa_enabled"
        aria-label="导出验证码"
    /></el-form-item>
    <el-form-item label="手机号验证"><PhoneVerification /></el-form-item>
    <el-form-item label="API密钥"
      ><span>{{ model.key_configured ? '已配置' : '未配置' }}</span
      ><el-button :disabled="busy || !password" @click="createKey">{{
        model.key_configured ? '重新创建密钥' : '创建密钥'
      }}</el-button></el-form-item
    >
    <el-alert
      v-if="key"
      type="success"
      :closable="false"
      title="密钥仅显示一次，请保存；重新创建会撤销旧密钥。"
    /><el-input v-if="key" :model-value="key" readonly aria-label="新创建的API密钥" />
    <el-form-item label="IP地址列表（每行一个，最多30个；留空不限制）"
      ><el-input v-model="ips" type="textarea" :rows="5" aria-label="安全IP地址列表"
    /></el-form-item>
    <el-form-item label="禁用密码登录"
      ><el-switch
        v-model="model.password_disabled"
        :disabled="!model.key_configured"
        aria-label="禁用密码登录"
      /><small>开启后可在登录页使用API密钥登录。启用双因子时还需输入验证码。</small></el-form-item
    >
    <el-button type="primary" :loading="busy" :disabled="!password" @click="save"
      >提交安全设置</el-button
    >
  </el-form>
</template>
