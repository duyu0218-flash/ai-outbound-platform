<script setup>
import { ref } from 'vue';
import { useData } from '../data';
import { request } from '../session';
import { perform } from '../operations';
const scripts = useData('/api/v1/workspace/scripts?size=200'),
  shares = useData('/api/v1/ops/benchmark/shares'),
  users = useData('/api/v1/ops/me'),
  open = ref(false),
  script = ref(''),
  names = ref([]),
  revision = ref(0),
  assignedCount = ref(0),
  reference = ref('grade'),
  groups = ref([]),
  applyExisting = ref(true),
  busy = ref(false);
async function select() {
  busy.value = true;
  try {
    const d = await request('/api/v1/workspace/scripts/' + script.value + '/share');
    revision.value = d.revision;
    assignedCount.value = d.assigned_count || 0;
    names.value = d.draft.usernames || [];
    reference.value = d.draft.reference || 'grade';
    groups.value = structuredClone(d.draft.intention_groups || []);
  } finally {
    busy.value = false;
  }
}
async function save() {
  const v = await perform(
    () =>
      request('/api/v1/workspace/scripts/' + script.value + '/share', 'PUT', {
        revision: revision.value,
        usernames: names.value,
        reference: reference.value,
        intention_groups: groups.value,
        apply_existing: applyExisting.value,
      }),
    shares.load,
  );
  if (v) open.value = false;
}
</script>
<template>
  <PageHeader title="分享设置" description="按已发布版本分享话术及意向评级规则"
    ><el-button
      type="primary"
      @click="
        open = true;
        script = '';
        names = [];
        groups = [];
        reference = 'grade';
      "
      >新建</el-button
    ><el-button @click="shares.load">刷新</el-button></PageHeader
  ><el-alert v-if="shares.error.value" :title="shares.error.value" type="error" /><el-card
    ><el-table :data="shares.data.value || []" border
      ><el-table-column prop="object_id" label="ID" /><el-table-column
        prop="name"
        label="话术"
      /><el-table-column label="意向与版本"
        ><template #default="{ row }"
          >发布版本 {{ row.draft.version }}（含评级规则）</template
        ></el-table-column
      ><el-table-column label="分享到用户"
        ><template #default="{ row }">{{
          row.draft.usernames.join('、')
        }}</template></el-table-column
      ><el-table-column label="操作"
        ><template #default="{ row }"
          ><el-button
            link
            @click="
              script = row.object_id;
              open = true;
              select();
            "
            >编辑</el-button
          ></template
        ></el-table-column
      ></el-table
    ></el-card
  ><el-dialog v-model="open" title="分享设置" width="760px"
    ><el-form label-position="top"
      ><el-form-item label="话术"
        ><el-select v-model="script" aria-label="分享话术" @change="select"
          ><el-option
            v-for="s in scripts.data.value?.rows || []"
            :key="s.id"
            :value="String(s.id)"
            :label="s.name"
            :disabled="!s.published" /></el-select></el-form-item
      ><el-form-item label="分享到用户"
        ><el-select v-model="names" multiple filterable aria-label="分享接收账号"
          ><el-option
            v-for="u in users.data.value?.users || []"
            :key="u.id"
            :value="u.username"
            :label="u.full_name || u.username" /></el-select></el-form-item
      ><el-form-item label="类型"><el-input model-value="按话术分配" disabled /></el-form-item>
      <el-form-item label="任务分配参考"
        ><el-select v-model="reference" aria-label="任务分配参考"
          ><el-option label="自定义评级" value="grade" /><el-option
            label="意向"
            value="intent" /><el-option label="通话结果" value="result_code" /></el-select
      ></el-form-item>
      <el-card v-for="(group, index) in groups" :key="index" :header="'意向分组 ' + (index + 1)">
        <el-input v-model="group.name" aria-label="意向分组名称" placeholder="分组名称" />
        <el-select
          v-model="group.values"
          multiple
          allow-create
          filterable
          default-first-option
          aria-label="意向分组条件"
          placeholder="输入评级或结果，留空匹配所有"
        />
        <el-select v-model="group.user_ids" multiple aria-label="意向分组接收人"
          ><el-option
            v-for="u in (users.data.value?.users || []).filter((u) => names.includes(u.username))"
            :key="u.id"
            :value="u.id"
            :label="u.full_name || u.username"
        /></el-select>
        <el-button @click="groups.splice(index, 1)">删除意向分组</el-button>
      </el-card>
      <el-button @click="groups.push({ name: '', values: [], user_ids: [] })"
        >添加意向分组</el-button
      >
      <el-form-item label="保存并应用于现有任务"
        ><el-switch v-model="applyExisting" aria-label="保存并应用于现有任务"
      /></el-form-item>
      <p>已分配数：{{ assignedCount }}</p>
      <el-alert
        title="保存后已分配数重置为0；新结果按分组顺序匹配并轮询分配，已有负责人保持不变。"
        type="info"
      />
      <el-button type="primary" :disabled="busy || !script" @click="save"
        >保存分享</el-button
      ></el-form
    ></el-dialog
  >
</template>
