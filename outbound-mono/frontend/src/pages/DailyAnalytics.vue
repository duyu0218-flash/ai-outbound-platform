<script setup>
import { ref, computed, watch } from 'vue';
import { useData } from '../data';
import { query } from '../operations';
const dayAt = (offset) =>
  new Date(Date.now() + 8 * 3600000 + offset * 86400000).toISOString().slice(0, 10);
const day = ref(dayAt(0)),
  from = ref(dayAt(-6)),
  until = ref(dayAt(0)),
  account = ref(''),
  intent = ref('');
const users = useData('/api/v1/ops/me');
const queue = useData('/api/v1/ops/dial-control');
function path(a, b, user) {
  if (!a || !b || a > b) return '';
  return (
    '/api/v1/ops/reports/daily?' +
    query({ from: a + 'T00:00:00+08:00', until: b + 'T23:59:59.999+08:00', agent_id: user })
  );
}
const daily = useData(() => path(day.value, day.value, account.value));
const range = useData(() => path(from.value, until.value, account.value));
const opts = computed(() => [...new Set((range.data.value || []).map((r) => r.intent_key))]);
watch(opts, (values) => {
  if (!values.includes(intent.value)) intent.value = '';
});
function sum(rows, key) {
  return rows.reduce((n, r) => n + Number(r[key]), 0);
}
function pct(a, b) {
  return b ? ((a * 100) / b).toFixed(2) + '%' : '—';
}
const stats = computed(() => {
  const rows = daily.data.value || [];
  return {
    attempts: sum(rows, 'attempts'),
    answers: sum(rows, 'answers'),
    seconds: sum(rows, 'seconds'),
    sessions: sum(rows, 'answered_sessions'),
  };
});
const trends = computed(() => {
  const buckets = new Map();
  for (const r of range.data.value || []) {
    if (!buckets.has(r.day_key))
      buckets.set(r.day_key, { day: r.day_key, attempts: 0, answers: 0, intents: 0 });
    const b = buckets.get(r.day_key);
    b.attempts += Number(r.attempts);
    b.answers += Number(r.answers);
    if (!intent.value || r.intent_key === intent.value) b.intents += Number(r.answered_sessions);
  }
  return [...buckets.values()];
});
const ceiling = computed(() => Math.max(1, ...trends.value.map((r) => r.attempts)));
</script>
<template>
  <PageHeader title="数据统计" description="按北京时间统计，以拨打尝试和接通回执核对外呼表现。" />
  <el-card header="每日统计"
    ><div class="toolbar">
      <label>日期筛选 <input type="date" v-model="day" aria-label="每日统计日期" /></label>
      <el-select
        v-model="account"
        clearable
        aria-label="统计坐席"
        placeholder="全部授权坐席"
        style="width: 220px"
        ><el-option
          v-for="u in users.data.value?.users || []"
          :key="u.id"
          :value="String(u.id)"
          :label="u.full_name + ' · ' + u.username"
      /></el-select>
      <el-button
        @click="
          daily.load();
          range.load();
        "
        >刷新统计</el-button
      >
    </div>
    <el-alert v-if="daily.error.value" :title="daily.error.value" type="error" />
    <p>
      外呼数量 {{ stats.attempts }} · 接通数量 {{ stats.answers }} · 接通率
      {{ pct(stats.answers, stats.attempts) }} · 通话时长 {{ stats.seconds.toFixed(2) }} 秒
    </p>
    <p v-if="queue.data.value">
      当前剩余外呼量
      {{ Number(queue.data.value.first_pending) + Number(queue.data.value.repeat_pending) }} ·
      待首拨 {{ queue.data.value.first_pending }} · 待复拨
      {{ queue.data.value.repeat_pending }}（租户当前队列）
    </p>
    <h3>接通会话意向分布</h3>
    <p
      v-for="r in (daily.data.value || []).filter((x) => Number(x.answered_sessions) > 0)"
      :key="r.intent_key"
    >
      {{ r.intent_key }} · {{ r.answered_sessions }} 通 ·
      {{ pct(r.answered_sessions, stats.sessions) }}
    </p>
    <el-empty
      v-if="!stats.sessions && !daily.loading.value"
      description="当日无接通会话"
    /> </el-card
  ><el-card header="区间统计"
    ><div class="toolbar">
      <label>开始日期 <input type="date" v-model="from" aria-label="统计开始日期" /></label
      ><label>结束日期 <input type="date" v-model="until" aria-label="统计结束日期" /></label>
      <el-select
        v-model="intent"
        clearable
        aria-label="意向筛选"
        placeholder="全部意向"
        style="width: 220px"
        ><el-option v-for="v in opts" :key="v" :value="v" :label="v"
      /></el-select>
    </div>
    <el-alert
      v-if="from > until || range.error.value"
      type="error"
      :title="range.error.value || '开始日期不能晚于结束日期'"
    />
    <p>
      区间外呼 {{ sum(trends, 'attempts') }} · 区间接通 {{ sum(trends, 'answers') }} · 接通率
      {{ pct(sum(trends, 'answers'), sum(trends, 'attempts')) }}
    </p>
    <el-table :data="trends" row-key="day"
      ><el-table-column prop="day" label="日期" /><el-table-column label="呼出趋势"
        ><template #default="{ row }"
          ><progress :value="row.attempts" :max="ceiling" /> {{ row.attempts }}</template
        ></el-table-column
      >
      <el-table-column label="接通趋势"
        ><template #default="{ row }"
          ><progress :value="row.answers" :max="ceiling" /> {{ row.answers }}</template
        ></el-table-column
      >
      <el-table-column label="意向趋势"
        ><template #default="{ row }"
          ><progress :value="row.intents" :max="ceiling" /> {{ row.intents }}</template
        ></el-table-column
      ></el-table
    ></el-card
  >
</template>
<style scoped>
progress {
  width: 60%;
  vertical-align: middle;
}
input[type='date'] {
  padding: 5px;
  border: 1px solid #c7ced8;
  border-radius: 4px;
}
</style>
