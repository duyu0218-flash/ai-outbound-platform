<script setup>
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { login } from '../session';
import { apiRequest } from '../api';
import { t, language, setLanguage } from '../i18n';
const props = defineProps({ role: String }),
  router = useRouter(),
  busy = ref(false),
  runtime = ref(null),
  error = ref(''),
  model = ref({ username: '', password: '' });
onMounted(async () => {
  try {
    runtime.value = await apiRequest('/api/v1/runtime');
  } catch (e) {
    error.value = e.message;
  }
});
async function submit() {
  busy.value = true;
  try {
    const user = await login(model.value.username, model.value.password, props.role);
    router.replace(user.role === 'admin' && props.role === 'admin' ? '/admin' : '/agent');
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <main class="login-page">
    <section class="login-visual">
      <div class="login-mark">◉</div>
      <h1>{{ t('product') }}</h1>
      <p>{{ t(role === 'admin' ? 'overviewHint' : 'workbenchHint') }}</p>
      <p v-for="key in ['aiOnly', 'aiHandoff', 'humanOnly', 'aiWithSms']" :key="key">
        ✓ {{ t(key) }}
      </p>
    </section>
    <el-card class="login-card"
      ><div class="actions">
        <el-tag>{{ t(role === 'admin' ? 'adminPortal' : 'agentPortal') }}</el-tag
        ><el-select :model-value="language" aria-label="语言" @change="setLanguage"
          ><el-option value="zh-CN" label="中文" /><el-option value="en-US" label="English"
        /></el-select>
      </div>
      <h2>{{ t('welcomeBack') }}</h2>
      <p>{{ t('loginHint') }}</p>
      <el-alert v-if="error" type="error" :title="error" /><FieldForm
        :model="model"
        :fields="[
          { key: 'username', label: 'username', required: true, autocomplete: 'username' },
          {
            key: 'password',
            label: 'password',
            type: 'password',
            required: true,
            autocomplete: 'current-password',
          },
        ]"
        :busy="busy"
        submit-label="signIn"
        @submit="submit"
      /><el-alert
        v-if="runtime?.demo_users_enabled"
        type="info"
        :title="t(role === 'admin' ? 'adminDemo' : 'agentDemo')"
      /><el-button link @click="router.push(role === 'admin' ? '/agent/login' : '/admin/login')">{{
        t(role === 'admin' ? 'agentPortal' : 'adminPortal')
      }}</el-button></el-card
    >
  </main>
</template>
