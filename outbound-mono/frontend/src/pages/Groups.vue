<script setup>
import { ref } from 'vue';
import { useData } from '../data';
import { t } from '../i18n';
import MetricCards from '../components/MetricCards.vue';
const days = ref(30),
  query = useData(() => '/api/v1/admin/contact-groups?days=' + days.value);
</script>
<template>
  <PageHeader title="groupMonitor" description="groupMonitorHint"
    ><el-select v-model="days" style="width: 130px"
      ><el-option
        v-for="value in [7, 30, 90]"
        :key="value"
        :value="value"
        :label="t('last' + value + 'Days')" /></el-select
    ><el-button @click="query.load">{{ t('refresh') }}</el-button></PageHeader
  ><el-alert v-if="query.error.value" :title="query.error.value" type="error" /><MetricCards
    :data="query.data.value?.summary"
    :items="[
      { key: 'contacts', label: 'contacts' },
      { key: 'dnc_contacts', label: 'dncContacts' },
      { key: 'calls', label: 'totalCalls' },
    ]"
  /><el-card :header="t('groupMonitorRows')"
    ><DataGrid
      :pagination="10"
      :rows="query.data.value?.rows"
      row-key="key"
      :loading="query.loading.value"
      :columns="[
        { key: 'label', label: 'group' },
        ...[
          'contacts',
          'dnc_contacts',
          'calls',
          'reached',
          'handoff',
          'completed',
          'failed',
          'no_answer',
          'loss',
        ].map((key) => ({
          key,
          label:
            { dnc_contacts: 'dncContacts', reached: 'reachedCalls', no_answer: 'noAnswer' }[key] ||
            key,
        })),
      ]"
  /></el-card>
</template>
