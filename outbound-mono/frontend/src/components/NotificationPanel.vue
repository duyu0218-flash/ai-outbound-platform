<script setup>
import { ref, computed } from 'vue';
import { useData } from '../data';
import { request } from '../session';
import { useRouter } from 'vue-router';
const props = defineProps({ role: String }),
  emit = defineEmits(['read']);
const open = ref(false),
  tab = ref('unread'),
  router = useRouter();
const feed = useData(() => (open.value ? '/api/v1/ops/notices' : ''));
const rows = computed(() =>
  (feed.data.value || [])
    .filter((r) => !r.deleted_at && (tab.value === 'read' ? r.read_at : !r.read_at))
    .slice(0, 20),
);
async function visit(row) {
  await request('/api/v1/ops/notices/' + row.id + '/read', 'POST');
  emit('read');
  open.value = false;
  router.push('/' + props.role + row.path);
}
</script>
<template>
  <el-popover
    v-model:visible="open"
    trigger="hover"
    placement="bottom-end"
    width="min(420px, calc(100vw - 24px))"
    ><template #reference><slot /></template
    ><el-tabs v-model="tab"
      ><el-tab-pane name="unread" label="未读" /><el-tab-pane name="read" label="已读" /></el-tabs
    ><el-alert v-if="feed.error.value" :title="feed.error.value" type="error" />
    <div v-for="row in rows" :key="row.id" class="record-card">
      <el-button link @click="visit(row)">{{ row.title }}</el-button
      ><small>{{ row.created_at }}</small>
    </div>
    <el-empty v-if="!rows.length" description="暂无消息" /><el-button
      link
      @click="
        open = false;
        router.push('/' + role + '/operations/notices');
      "
      >打开消息中心</el-button
    ></el-popover
  >
</template>
