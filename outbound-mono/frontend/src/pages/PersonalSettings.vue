<script setup>
import { reactive, ref } from 'vue';
import { request, logout, session } from '../session';
import { perform } from '../operations';
import SettingSection from '../components/SettingSection.vue';
import AccountSecurity from '../components/AccountSecurity.vue';
import PersonalProfile from '../components/PersonalProfile.vue';
import SuccessIntercept from '../components/SuccessIntercept.vue';
const password = reactive({ old_password: '', new_password: '', confirm_password: '', code: '' }),
  busy = ref(false);
async function save() {
  busy.value = true;
  try {
    const v = await perform(() => request('/api/v1/auth/password', 'POST', password));
    if (v) {
      const loginPath = session.user?.role === 'admin' ? '/admin/login' : '/agent/login';
      await logout().catch(() => {});
      location.assign(loginPath);
    }
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <PageHeader title="个人设置" /><el-card
    ><el-tabs
      ><el-tab-pane label="密码设置"
        ><el-form label-position="top" style="max-width: 440px" @submit.prevent="save"
          ><el-form-item
            v-for="[key, label] in [
              ['old_password', '旧密码'],
              ['new_password', '新密码'],
              ['confirm_password', '确认密码'],
            ]"
            :key="key"
            :label="label"
            ><el-input
              v-model="password[key]"
              :aria-label="label"
              type="password"
              show-password
              :autocomplete="key === 'old_password' ? 'current-password' : 'new-password'"
          /></el-form-item>
          <p>新密码须为8至20位，包含大写、小写字母、数字及特殊符号。</p>
          <el-form-item label="双因子验证码（已开启时填写）"
            ><el-input
              v-model="password.code"
              maxlength="6"
              aria-label="修改密码双因子验证码"
              autocomplete="one-time-code" /></el-form-item
          ><el-button type="primary" native-type="submit" :loading="busy"
            >修改密码</el-button
          ></el-form
        ></el-tab-pane
      ><el-tab-pane label="安全设置"
        ><AccountSecurity /><el-descriptions border :column="1"
          ><el-descriptions-item label="账号">{{ session.user?.username }}</el-descriptions-item
          ><el-descriptions-item label="角色">{{ session.user?.role }}</el-descriptions-item
          ><el-descriptions-item label="租户">{{
            session.user?.tenant_id
          }}</el-descriptions-item></el-descriptions
        ><el-button @click="logout().then(() => location.assign('/admin/login'))"
          >退出当前登录</el-button
        ></el-tab-pane
      ><el-tab-pane label="并发设置" lazy><SettingSection section="capacity" /></el-tab-pane
      ><el-tab-pane label="基础设置" lazy><PersonalProfile /></el-tab-pane
      ><el-tab-pane label="拦截设置" lazy
        ><SuccessIntercept /><SettingSection section="compliance" /></el-tab-pane></el-tabs
  ></el-card>
</template>
