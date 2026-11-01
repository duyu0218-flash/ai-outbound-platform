<script setup>
import { ref, computed } from 'vue';
import { request } from '../session';
import { useData, action } from '../data';
import { formatDate } from '../api';

const day = (offset) => {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  return d.toLocaleDateString('sv-SE');
};
const journeyId = ref(''),
  snapshotId = ref(''),
  channel = ref(''),
  range = ref([day(-6), day(0)]),
  refreshing = ref(false);
const journeys = useData('/api/v1/marketing/journeys');
const query = useData(
  () =>
    '/api/v1/marketing/dashboard?' +
    new URLSearchParams({
      journeyId: journeyId.value,
      snapshotId: snapshotId.value,
      channel: channel.value,
      from: range.value?.[0] || '',
      to: range.value?.[1] || '',
    }),
);
const d = computed(() => query.data.value || {});
const stepText = {
  selected: '圈选人数',
  contacted: '触达（送达/接通）',
  engaged: '互动（点击/有效对话）',
  intent: '意向 A+B',
  converted: '转化',
};
const pct = (v) => (v === null || v === undefined ? '-' : (Number(v) * 100).toFixed(1) + '%');
const pp = (v) =>
  v === null || v === undefined
    ? '-'
    : (Number(v) >= 0 ? '+' : '') + (Number(v) * 100).toFixed(1) + ' 个百分点';
const money = (v) => (v === null || v === undefined ? '-' : '¥' + Number(v).toFixed(2));
const funnel = computed(() => {
  const steps = d.value.funnel || [],
    top = Math.max(1, steps[0]?.value || 0);
  return steps.map((s, i) => ({
    ...s,
    label: stepText[s.step],
    width: Math.max(2, (s.value / top) * 100),
    ratio: i === 0 ? '' : pct(steps[i - 1].value ? s.value / steps[i - 1].value : null),
  }));
});
const cards = computed(() => [
  {
    label: '短信送达率',
    value: pct(d.value.sms_delivery_rate),
    hint: `送达 ${d.value.sms_delivered ?? 0} / 发送 ${d.value.sms_sent ?? 0}`,
  },
  {
    label: '短链点击率',
    value: pct(d.value.click_rate),
    hint: `点击 ${d.value.link_clicks ?? 0} / 送达`,
  },
  {
    label: '外呼接通率',
    value: pct(d.value.call_connect_rate),
    hint: `接通 ${d.value.call_answered ?? 0} / 拨打 ${d.value.calls ?? 0}`,
  },
  { label: '秒挂率', value: pct(d.value.instant_hangup_rate), hint: '占接通' },
  { label: '语音助手占比', value: pct(d.value.voice_assistant_rate), hint: '占接通' },
  { label: '有效对话率', value: pct(d.value.conversation_rate), hint: '占接通' },
  { label: '意向率', value: pct(d.value.intent_rate), hint: '意向 A+B / 触达人数' },
  { label: '转化率', value: pct(d.value.conversion_rate), hint: '转化 / 圈选（实验组）' },
  {
    label: '增量转化',
    value: pp(d.value.incremental_conversion),
    hint: `对照组 ${pct(d.value.control_conversion_rate)}（${d.value.control_converted ?? 0}/${d.value.control_selected ?? 0}）`,
  },
  { label: '总成本', value: money(d.value.cost), hint: '短信按发送、外呼按拨打计' },
  { label: '单个意向成本', value: money(d.value.cost_per_intent), hint: '总成本 / 意向人数' },
]);
const tableColumns = (first) => [
  first,
  { key: 'selected', label: '圈选' },
  { key: 'contacted', label: '触达' },
  { key: 'engaged', label: '互动' },
  { key: 'intent', label: '意向' },
  { key: 'converted', label: '转化' },
  { key: 'sms_delivery_rate', label: '送达率', render: pct },
  { key: 'call_connect_rate', label: '接通率', render: pct },
  { key: 'conversion_rate', label: '转化率', render: pct },
  { key: 'incremental_conversion', label: '增量转化', render: pp },
  { key: 'cost_per_intent', label: '单个意向成本', render: money },
];
async function refresh() {
  refreshing.value = true;
  try {
    await action(
      () =>
        request('/api/v1/marketing/dashboard/refresh', 'POST', {
          from: range.value?.[0],
          to: range.value?.[1],
        }),
      { refresh: query.load, success: '已重新汇总' },
    );
  } finally {
    refreshing.value = false;
  }
}
</script>
<template>
  <PageHeader
    title="营销漏斗看板"
    description="圈选 → 触达 → 互动 → 意向 → 转化的全漏斗、各环节比率、对照组增量与单个意向成本。数据按小时汇总。"
    ><el-button :loading="refreshing" @click="refresh">重新汇总所选日期</el-button></PageHeader
  >
  <el-card>
    <div class="actions">
      <el-select v-model="journeyId" clearable placeholder="全部活动" style="width: 200px"
        ><el-option
          v-for="j in journeys.data.value?.items || []"
          :key="j.id"
          :value="j.id"
          :label="j.name"
      /></el-select>
      <el-input v-model="snapshotId" clearable placeholder="人群快照编号" style="width: 200px" />
      <el-date-picker
        v-model="range"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        :clearable="false"
      />
      <el-radio-group v-model="channel"
        ><el-radio-button value="">全部渠道</el-radio-button
        ><el-radio-button value="sms">短信</el-radio-button
        ><el-radio-button value="call">外呼</el-radio-button></el-radio-group
      >
      <span class="hint">汇总至 {{ formatDate(d.latest_hour) }}</span>
    </div>
    <el-alert v-if="query.error.value" :title="query.error.value" type="error" />
  </el-card>

  <el-card header="转化漏斗（实验组）" style="margin-top: 12px" v-loading="query.loading.value">
    <div v-for="s in funnel" :key="s.step" class="funnel-row">
      <span class="funnel-label">{{ s.label }}</span>
      <div class="funnel-track">
        <div class="funnel-bar" :style="{ width: s.width + '%' }">{{ s.value }}</div>
      </div>
      <span class="hint funnel-ratio">{{ s.ratio ? '较上一步 ' + s.ratio : '' }}</span>
    </div>
  </el-card>

  <div class="metrics" style="margin-top: 12px">
    <el-card v-for="c in cards" :key="c.label"
      ><span class="hint">{{ c.label }}</span>
      <div class="metric-value">{{ c.value }}</div>
      <small class="hint">{{ c.hint }}</small></el-card
    >
  </div>

  <el-card header="按活动" style="margin-top: 12px">
    <DataGrid
      :rows="d.journeys || []"
      :columns="tableColumns({ key: 'name', label: '活动' })"
      row-key="journey_id"
    />
  </el-card>
  <el-card header="按日期" style="margin-top: 12px">
    <DataGrid
      :rows="d.daily || []"
      :columns="tableColumns({ key: 'date', label: '日期' })"
      row-key="date"
    />
  </el-card>
</template>
<style scoped>
.funnel-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 8px 0;
}
.funnel-label {
  width: 150px;
  flex: none;
}
.funnel-track {
  flex: 1;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}
.funnel-bar {
  background: var(--el-color-primary);
  color: #fff;
  border-radius: 4px;
  padding: 4px 8px;
  min-width: 32px;
  box-sizing: border-box;
  white-space: nowrap;
}
.funnel-ratio {
  width: 130px;
  flex: none;
}
</style>
