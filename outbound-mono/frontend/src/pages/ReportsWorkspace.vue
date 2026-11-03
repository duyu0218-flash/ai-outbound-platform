<script setup>
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import Reports from './Reports.vue';
import OperationsReports from './OperationsReports.vue';
import Groups from './Groups.vue';
const route = useRoute(),
  router = useRouter();
const tabs = [
  ['summary', '运营报表'],
  ['analysis', '运营分析'],
  ['groups', '分组统计'],
];
const active = computed(() =>
  tabs.some(([k]) => k === route.query.tab) ? route.query.tab : 'summary',
);
</script>
<template>
  <el-tabs
    :model-value="active"
    @update:model-value="(v) => router.replace({ query: { ...route.query, tab: v } })"
    ><el-tab-pane v-for="[key, label] in tabs" :key="key" :name="key" :label="label" /></el-tabs
  ><Reports v-if="active === 'summary'" role="admin" /><OperationsReports
    v-else-if="active === 'analysis'"
    role="admin"
  /><Groups v-else-if="active === 'groups'" role="admin" />
</template>
