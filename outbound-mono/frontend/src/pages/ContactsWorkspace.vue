<script setup>
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import Contacts from './Contacts.vue';
import ContactBatchList from './ContactBatchList.vue';
import ContactOperations from './ContactOperations.vue';
const route = useRoute(),
  router = useRouter();
const tabs = [
  ['list', '客户名单'],
  ['batches', '导入批次'],
  ['operations', '分配与跟进'],
];
const active = computed(() =>
  tabs.some(([k]) => k === route.query.tab) ? route.query.tab : 'list',
);
</script>
<template>
  <el-tabs
    :model-value="active"
    @update:model-value="(v) => router.replace({ query: { ...route.query, tab: v } })"
    ><el-tab-pane v-for="[key, label] in tabs" :key="key" :name="key" :label="label" /></el-tabs
  ><Contacts v-if="active === 'list'" role="admin" /><ContactBatchList
    v-else-if="active === 'batches'"
    role="admin"
  /><ContactOperations v-else-if="active === 'operations'" role="admin" />
</template>
