<script setup>
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import Calls from './Calls.vue';
import OperationsCalls from './OperationsCalls.vue';
const route = useRoute(),
  router = useRouter();
const tabs = [
  ['records', '通话记录'],
  ['detail', '话单与技术明细'],
];
const active = computed(() =>
  tabs.some(([k]) => k === route.query.tab) ? route.query.tab : 'records',
);
</script>
<template>
  <el-tabs
    :model-value="active"
    @update:model-value="(v) => router.replace({ query: { ...route.query, tab: v } })"
    ><el-tab-pane v-for="[key, label] in tabs" :key="key" :name="key" :label="label" /></el-tabs
  ><Calls v-if="active === 'records'" role="admin" /><OperationsCalls
    v-else-if="active === 'detail'"
    role="admin"
  />
</template>
