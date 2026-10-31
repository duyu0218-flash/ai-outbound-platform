<script setup>
import { ref, reactive, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { clientSession, clientRequest } from './session';
const members = ref([]),
  open = ref(false),
  model = reactive({
    username: '',
    full_name: '',
    phone: '',
    grants: { phone_reveal: false, recording: false },
  });
async function load() {
  try {
    members.value = await clientRequest('/api/v1/client/members');
  } catch (e) {
    ElMessage.error(e.message);
  }
}
onMounted(load);
function showPassword(user) {
  ElMessageBox.alert(
    `成员 ${user.username} 的初始密码：${user.initial_password}\n关闭后不再显示，请转交本人并提醒首次登录修改。`,
    '一次性密码',
    { type: 'warning' },
  );
}
async function create() {
  try {
    const user = await clientRequest('/api/v1/client/members', 'POST', model);
    open.value = false;
    Object.assign(model, { username: '', full_name: '', phone: '' });
    showPassword(user);
    load();
  } catch (e) {
    ElMessage.error(e.message);
  }
}
async function update(row, body) {
  try {
    const user = await clientRequest('/api/v1/client/members/' + row.id, 'PUT', body);
    if (user.initial_password) showPassword(user);
    load();
  } catch (e) {
    ElMessage.error(e.message);
  }
}
</script>
<template>
  <el-card>
    <div class="actions">
      <el-button type="primary" @click="open = true">添加成员</el-button>
      <span class="muted">成员只能看到分配给自己的线索；平台未开放的能力无法授予。</span>
    </div>
    <el-table :data="members" stripe>
      <el-table-column prop="username" label="用户名" />
      <el-table-column prop="full_name" label="姓名" />
      <el-table-column label="角色"
        ><template #default="{ row }">{{
          row.role === 'owner' ? '企业管理员' : '成员'
        }}</template></el-table-column
      >
      <el-table-column label="查看完整号码"
        ><template #default="{ row }"
          ><el-switch
            v-if="row.role === 'member'"
            :model-value="!!row.grants?.phone_reveal"
            @change="
              (v) => update(row, { grants: { ...row.grants, phone_reveal: v } })
            " /></template
      ></el-table-column>
      <el-table-column label="播放录音"
        ><template #default="{ row }"
          ><el-switch
            v-if="row.role === 'member'"
            :model-value="!!row.grants?.recording"
            @change="(v) => update(row, { grants: { ...row.grants, recording: v } })" /></template
      ></el-table-column>
      <el-table-column label="操作" width="220"
        ><template #default="{ row }"
          ><template v-if="row.role === 'member'"
            ><el-button size="small" @click="update(row, { action: 'reset_password' })"
              >重置密码</el-button
            ><el-button
              size="small"
              :type="row.enabled ? 'danger' : 'success'"
              @click="update(row, { enabled: !row.enabled })"
              >{{ row.enabled ? '停用' : '启用' }}</el-button
            ></template
          ></template
        ></el-table-column
      >
    </el-table>
    <el-dialog v-model="open" title="添加成员" width="480px">
      <el-form label-width="80px">
        <el-form-item label="用户名" required
          ><el-input v-model="model.username" aria-label="成员用户名"
        /></el-form-item>
        <el-form-item label="姓名" required
          ><el-input v-model="model.full_name" aria-label="成员姓名"
        /></el-form-item>
        <el-form-item label="手机号"
          ><el-input v-model="model.phone" aria-label="成员手机号"
        /></el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="open = false">取消</el-button
        ><el-button type="primary" @click="create">添加</el-button></template
      >
    </el-dialog>
  </el-card>
</template>
