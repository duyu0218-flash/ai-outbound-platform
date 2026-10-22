<script setup>
import { t } from '../i18n';
defineProps({ data: Object, items: Array });
</script>
<template>
  <div class="metrics">
    <el-card v-for="item in items" :key="item.key"
      ><span class="hint">{{ t(item.label || item.key) }}</span>
      <div class="metric-value">
        {{ item.render ? item.render(data?.[item.key]) : (data?.[item.key] ?? 0)
        }}<small v-if="item.suffix"> {{ item.suffix }}</small>
      </div></el-card
    >
  </div>
</template>
