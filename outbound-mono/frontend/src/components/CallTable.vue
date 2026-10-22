<script setup>
import { t } from '../i18n';
import { terminals } from '../data';
defineProps({ rows: Array, loading: Boolean });
defineEmits(['details', 'command']);
</script>
<template>
  <DataGrid
    :rows="rows"
    :loading="loading"
    :columns="[
      { key: 'phone', label: 'phone' },
      { key: 'mode', label: 'mode', translate: true },
      { key: 'status', label: 'status', translate: true, tag: true },
      {
        key: 'attempts',
        label: 'attempts',
        render: (value, row) => value + '/' + row.max_attempts,
      },
      { key: 'campaign_id', label: 'campaignId' },
      { key: 'created_at', label: 'createdAt', date: true },
    ]"
    ><template #actions="{ row }"
      ><el-button size="small" @click="$emit('details', row)">{{ t('events') }}</el-button
      ><el-button
        size="small"
        :disabled="!['dialing', 'answered', 'in_ai', 'waiting_human'].includes(row.status)"
        @click="$emit('command', row, 'handover')"
        >{{ t('handoff') }}</el-button
      ><el-button
        size="small"
        type="danger"
        :disabled="terminals.includes(row.status)"
        @click="$emit('command', row, 'hangup')"
        >{{ t('hangup') }}</el-button
      ><el-button
        size="small"
        :disabled="!terminals.includes(row.status) || row.attempts >= row.max_attempts"
        @click="$emit('command', row, 'retry')"
        >{{ t('retry') }}</el-button
      ></template
    ></DataGrid
  >
</template>
