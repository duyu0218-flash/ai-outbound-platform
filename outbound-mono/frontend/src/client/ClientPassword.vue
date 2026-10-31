<script setup>
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { clientSession, changePassword } from './session';
const router = useRouter(),
  busy = ref(false),
  model = ref({ old: '', next: '', confirm: '' });
async function submit() {
  if (model.value.next !== model.value.confirm) return ElMessage.error('两次输入的新密码不一致');
  busy.value = true;
  try {
    await changePassword(model.value.old, model.value.next);
    ElMessage.success('密码已修改');
    router.replace('/client');
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <el-card style="max-width: 520px">
    <el-alert
      v-if="clientSession.user?.must_change_password"
      type="warning"
      :closable="false"
      title="首次登录，请先将初始密码修改为您自己的密码"
    />
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="当前密码"
        ><el-input
          v-model="model.old"
          aria-label="当前密码"
          type="password"
          show-password
          autocomplete="current-password"
      /></el-form-item>
      <el-form-item label="新密码（至少 10 位，含字母和数字）"
        ><el-input
          v-model="model.next"
          aria-label="新密码"
          type="password"
          show-password
          autocomplete="new-password"
      /></el-form-item>
      <el-form-item label="确认新密码"
        ><el-input
          v-model="model.confirm"
          aria-label="确认新密码"
          type="password"
          show-password
          autocomplete="new-password"
      /></el-form-item>
      <el-button type="primary" native-type="submit" :loading="busy">保存</el-button>
    </el-form>
  </el-card>
</template>
