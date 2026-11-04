<script setup>
import { useData } from '../data';
const shares = useData('/api/v1/ops/shared-scripts');
</script>
<template>
  <PageHeader title="收到的话术" description="查看共享给本账号的发布版本"
    ><el-button @click="shares.load()">刷新共享话术</el-button></PageHeader
  ><el-alert v-if="shares.error.value" :title="shares.error.value" type="error" /><el-card
    v-for="s in shares.data.value || []"
    :key="s.object_id"
    ><h3>{{ s.name }} · v{{ s.version }}</h3>
    <p style="white-space: pre-wrap">{{ s.content }}</p></el-card
  ><el-empty
    v-if="!shares.loading.value && !shares.data.value?.length"
    description="没有共享话术"
  />
</template>
