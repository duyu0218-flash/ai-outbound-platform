<script setup>
import { ref, onMounted } from 'vue';
import { request } from '../session';
const props = defineProps({ kind: String });
const emit = defineEmits(['changed']);
const rows = ref([]),
  error = ref(''),
  open = ref(false),
  editing = ref(null),
  model = ref({}),
  refs = ref(null),
  busy = ref(false);
const group = { business: '业务', region: '地区', language: '语言' };
async function load() {
  try {
    rows.value = await request('/api/v1/workspace/catalog/' + props.kind);
  } catch (e) {
    error.value = e.message;
  }
}
function edit(row) {
  editing.value = row;
  model.value = row
    ? JSON.parse(JSON.stringify(row.draft))
    : {
        name: '',
        enabled: true,
        group: 'business',
        description: '',
        credential_ref: '',
        gateway_url: '',
      };
  open.value = true;
  error.value = '';
}
async function save() {
  busy.value = true;
  try {
    await request(
      '/api/v1/workspace/catalog/' +
        props.kind +
        '/' +
        (editing.value?.object_id || crypto.randomUUID()),
      'PUT',
      { draft: model.value, expected_revision: editing.value?.revision || 0 },
    );
    open.value = false;
    await load();
    emit('changed');
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function references(row) {
  try {
    refs.value = await request(
      '/api/v1/workspace/catalog/' + props.kind + '/' + row.object_id + '/references',
    );
  } catch (e) {
    error.value = e.message;
  }
}
onMounted(load);
</script>
<template>
  <el-card :header="kind === 'skill' ? '接听技能字典' : '已配置网关连接'"
    ><el-alert v-if="error" type="error" :title="error" /><el-button @click="edit()">{{
      kind === 'skill' ? '新增技能' : '新增连接'
    }}</el-button
    ><el-table :data="rows"
      ><el-table-column prop="name" label="名称" /><el-table-column label="分组 / 引用"
        ><template #default="{ row }">{{
          kind === 'skill' ? group[row.draft.group] : row.draft.credential_ref
        }}</template></el-table-column
      ><el-table-column label="状态"
        ><template #default="{ row }"
          >{{ row.enabled ? '启用' : '停用'
          }}{{
            kind === 'connection'
              ? row.credential_configured
                ? ' · 凭据已配置'
                : ' · 服务端凭据未配置'
              : ''
          }}</template
        ></el-table-column
      ><el-table-column label="操作"
        ><template #default="{ row }"
          ><el-button link @click="edit(row)">编辑</el-button
          ><el-button link @click="references(row)">查看引用影响</el-button></template
        ></el-table-column
      ></el-table
    >
    <p v-if="kind === 'skill'">
      停用技能后不能新增选择，已有团队和发布话术保留原技能，需查看引用后逐项调整。被引用的技能不可直接改名。
    </p>
    <p v-else>
      此处仅保存连接名称、网关地址与服务端凭据引用，不保存密钥。连接变更不自动改写已有线路；停用连接只阻止新选择，停止拨号需停用对应线路。凭据存在不等于联调通过。
    </p></el-card
  >
  <el-dialog v-model="open" :title="kind === 'skill' ? '技能配置' : '连接配置'" width="600px"
    ><el-alert v-if="error" type="error" :title="error" /><el-form label-position="top"
      ><el-form-item label="名称"
        ><el-input
          v-model="model.name"
          :aria-label="kind === 'skill' ? '技能名称' : '连接名称'" /></el-form-item
      ><template v-if="kind === 'skill'"
        ><el-form-item label="技能分组"
          ><el-select v-model="model.group" aria-label="技能分组"
            ><el-option
              v-for="(label, key) in group"
              :key="key"
              :label="label"
              :value="key" /></el-select></el-form-item
        ><el-form-item label="说明"
          ><el-input v-model="model.description" /></el-form-item></template
      ><template v-else
        ><el-form-item label="网关地址"
          ><el-input v-model="model.gateway_url" aria-label="连接网关地址" /></el-form-item
        ><el-form-item label="服务端凭据引用"
          ><el-input
            v-model="model.credential_ref"
            aria-label="连接凭据引用" /></el-form-item></template
      ><el-switch v-model="model.enabled" active-text="启用" /></el-form
    ><template #footer
      ><el-button @click="open = false">取消</el-button
      ><el-button type="primary" :loading="busy" @click="save"
        >保存{{ kind === 'skill' ? '技能' : '连接' }}</el-button
      ></template
    ></el-dialog
  >
  <el-dialog :model-value="!!refs" title="引用影响" @close="refs = null"
    ><el-table :data="refs || []"
      ><el-table-column prop="kind" label="类型" /><el-table-column
        prop="name"
        label="名称" /></el-table
    ><el-empty v-if="!refs?.length" description="暂无引用"
  /></el-dialog>
</template>
