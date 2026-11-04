<script setup>
import { ref } from 'vue';
import { useData } from '../data';
import { t } from '../i18n';
import MetricCards from '../components/MetricCards.vue';
const days = ref(30),
  dashboard = useData(() => '/api/v1/admin/dashboard?days=' + days.value),
  tasks = useData('/api/v1/workspace/tasks?page=1&size=6');
</script>
<template>
  <PageHeader title="overview" description="overviewHint"
    ><el-select v-model="days" style="width: 160px"
      ><el-option
        v-for="value in [7, 30, 90]"
        :key="value"
        :value="value"
        :label="t('last' + value + 'Days')" /></el-select></PageHeader
  ><el-alert v-if="dashboard.error.value" :title="dashboard.error.value" type="error" /><MetricCards
    :data="dashboard.data.value?.period"
    :items="[
      { key: 'calls', label: 'periodCalls' },
      { key: 'reached', label: 'reachedCalls' },
      { key: 'interested', label: 'interestedLeads' },
      { key: 'reach_rate', label: 'reachRate', suffix: '%' },
    ]"
  />
  <div class="split">
    <el-card header="最近外呼任务"
      ><el-alert v-if="tasks.error.value" :title="tasks.error.value" type="error" /><DataGrid
        :rows="tasks.data.value?.rows || []"
        :loading="tasks.loading.value"
        :columns="[
          { key: 'name', label: '任务名称' },
          { key: 'contact_count', label: '名单人数' },
          { key: 'status', label: '任务状态', translate: true, tag: true },
          { key: 'concurrency', label: '同时通话上限' },
        ]"
        ><template #actions="{ row }"
          ><router-link :to="{ path: '/admin/operations/calls', query: { campaign_id: row.id } }"
            >查看任务话单</router-link
          ></template
        ></DataGrid
      >
      <router-link to="/admin/campaigns"
        ><el-button link>管理外呼任务</el-button></router-link
      > </el-card
    ><el-card :header="t('quickActions')"
      ><div class="flow-palette">
        <router-link
          v-for="[path, label] in [
            ['contacts', 'addContact'],
            ['scripts', 'createScript'],
            ['campaigns', 'createCampaign'],
            ['calls', 'startCall'],
          ]"
          :key="path"
          :to="'/admin/' + path"
          ><el-button>{{ t(label) }}</el-button></router-link
        >
      </div></el-card
    >
  </div>
  <el-card :header="t('campaignPerformance')"
    ><DataGrid
      :rows="dashboard.data.value?.campaign_performance"
      row-key="campaign_id"
      :columns="[
        { key: 'campaign_id', label: 'campaignId' },
        { key: 'name', label: 'name' },
        { key: 'calls', label: 'periodCalls' },
        { key: 'reached', label: 'reachedCalls' },
        { key: 'reach_rate', label: 'reachRate', render: (v) => v + '%' },
        { key: 'interested', label: 'interestedLeads' },
        { key: 'interest_rate', label: 'interestRate', render: (v) => v + '%' },
      ]"
    />
    <p class="hint">
      {{ dashboard.data.value?.metric_definitions?.reached }}；{{
        dashboard.data.value?.metric_definitions?.interested
      }}
    </p></el-card
  >
</template>
