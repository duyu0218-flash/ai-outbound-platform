<script setup>
import { ref, reactive } from 'vue';
import { useData } from '../data';
import { t } from '../i18n';
import MetricCards from '../components/MetricCards.vue';
const dimension = ref('campaign'),
  days = ref(30),
  prices = reactive({ telephony: 0, ai: 0, sms: 0 }),
  query = useData(
    () =>
      `/api/v1/admin/billing?dimension=${dimension.value}&days=${days.value}&telephony_unit_price_per_minute=${prices.telephony}&ai_unit_price_per_minute=${prices.ai}&sms_unit_price=${prices.sms}`,
  );
const columns = [
  { key: 'label', label: 'dimension' },
  ...[
    'calls',
    'billable_calls',
    'reached',
    'handoff',
    'completed',
    'failed',
    'no_answer',
    'loss',
    'telephony_minutes',
    'missing_duration_count',
    'ai_minutes',
    'sms_count',
    'estimated_cost',
  ].map((key) => ({
    key,
    label:
      {
        billable_calls: 'billableCalls',
        reached: 'reachedCalls',
        no_answer: 'noAnswer',
        telephony_minutes: '电话分钟',
        missing_duration_count: '缺失时长轮次',
        ai_minutes: 'aiMinutes',
        sms_count: 'smsCount',
        estimated_cost: 'estimatedCost',
      }[key] || key,
    ...(['telephony_minutes', 'ai_minutes', 'estimated_cost'].includes(key)
      ? { render: (value) => Number(value || 0).toFixed(key === 'estimated_cost' ? 4 : 2) }
      : {}),
  })),
];
</script>
<template>
  <PageHeader title="billing" description="billingHint"
    ><el-select v-model="dimension" style="width: 140px"
      ><el-option
        v-for="value in ['campaign', 'agent', 'line']"
        :key="value"
        :value="value"
        :label="t(value)" /></el-select
    ><el-select v-model="days" style="width: 130px"
      ><el-option
        v-for="value in [7, 30, 90]"
        :key="value"
        :value="value"
        :label="t('last' + value + 'Days')" /></el-select
    ><el-button @click="query.load">{{ t('refresh') }}</el-button></PageHeader
  ><el-card
    ><div class="toolbar">
      <el-form-item
        v-for="key in ['telephony', 'ai', 'sms']"
        :key="key"
        :label="
          t(
            { telephony: 'billingTelephonyPrice', ai: 'billingAiPrice', sms: 'billingSmsPrice' }[
              key
            ],
          )
        "
        ><el-input-number v-model="prices[key]" :min="0" :precision="6"
      /></el-form-item></div></el-card
  ><el-alert
    type="info"
    title="费用为按秒折算的估算值，非运营商结算账单"
    :description="`话费使用每轮拨号的 CDR 时长，缺少 CDR 时使用接通至结束的观测时长；AI 使用接通至转人工或结束的占用时长。缺少话费时长 ${query.data.value?.summary?.missing_duration_count || 0} 轮，缺少 AI 时长 ${query.data.value?.summary?.missing_ai_duration_count || 0} 轮，观测估算 ${query.data.value?.summary?.estimated_duration_count || 0} 轮；缺失部分未计价。`"
  /><el-alert v-if="query.error.value" :title="query.error.value" type="error" /><MetricCards
    :data="query.data.value?.summary"
    :items="[
      { key: 'calls', label: 'total' },
      { key: 'billable_calls', label: 'billableCalls' },
      { key: 'estimated_cost', label: 'estimatedCost', render: (v) => Number(v || 0).toFixed(4) },
      { key: 'ai_minutes', label: 'aiMinutes', suffix: 'min' },
    ]"
  /><el-card :header="t('billingRows')"
    ><DataGrid
      :pagination="10"
      :rows="query.data.value?.rows"
      :columns="columns"
      row-key="key"
      :loading="query.loading.value"
  /></el-card>
</template>
