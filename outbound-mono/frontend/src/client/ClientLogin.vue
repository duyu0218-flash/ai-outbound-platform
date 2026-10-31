<script setup>
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { clientLogin } from './session';
const router = useRouter(),
  busy = ref(false),
  model = ref({ username: '', password: '' });
async function submit() {
  if (!model.value.username || !model.value.password) return;
  busy.value = true;
  try {
    const user = await clientLogin(model.value.username, model.value.password);
    router.replace(user.must_change_password ? '/client/password' : '/client');
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
      <h1>企业线索门户</h1>
      <p>查看平台为贵司筛选并经质检确认的意向客户，及时跟进并反馈结果。</p>
      <p>✓ 质检通过即时交付</p>
      <p>✓ 跟进状态与平台同步</p>
      <p>✓ 号码脱敏与访问留痕</p>
    </section>
    <el-card class="login-card">
      <el-tag>企业门户</el-tag>
      <h2>登录</h2>
      <p>账号由平台开通，如需开通或找回请联系您的客户经理。</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名"
          ><el-input
            v-model="model.username"
            aria-label="用户名"
            autocomplete="username"
            name="username"
        /></el-form-item>
        <el-form-item label="密码"
          ><el-input
            v-model="model.password"
            aria-label="密码"
            type="password"
            show-password
            autocomplete="current-password"
            name="password"
            @keyup.enter="submit"
        /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="busy" style="width: 100%"
          >登录</el-button
        >
      </el-form>
    </el-card>
  </main>
</template>
