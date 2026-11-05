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
  busy = ref(false);
async function select() {
  busy.value = true;
  try {
    const d = await request('/api/v1/workspace/scripts/' + script.value + '/share');
    revision.value = d.revision;
    names.value = d.draft.usernames || [];
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
  ><el-dialog v-model="open" title="分享设置" width="560px"
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
      ><el-button type="primary" :disabled="busy || !script" @click="save"
        >保存分享</el-button
      ></el-form
    ></el-dialog
  >
</template>
