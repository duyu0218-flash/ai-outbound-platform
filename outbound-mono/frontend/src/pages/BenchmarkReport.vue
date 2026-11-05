<script setup>
import { computed, ref, reactive } from 'vue';
import { useRoute } from 'vue-router';
import { useData } from '../data';
import { ops, perform, query } from '../operations';
import { reportLabels } from '../benchmark';
import OperationsReports from './OperationsReports.vue';
const route = useRoute(),
  kind = computed(() => route.path.split('/').at(-1));
const titles = {
  call: '呼叫统计',
  seating: '账号统计',
  intercept: '拦截统计',
  intent: '意向统计',
  followUp: '跟进统计',
  textMsg: '短信统计',
  dataAnalysis: '数据分析',
  recharge: '充值中心',
};
const advanced = computed(() => ['seating', 'dataAnalysis'].includes(kind.value));
const today = new Date(Date.now() + 8 * 3600000).toISOString().slice(0, 10);
const filters = reactive({
    from: today.slice(0, 7) + '-01',
    until: today,
    actor_id: '',
    campaign_id: '',
    dimension: 'agent',
    script_id: '',
    answered: '',
  }),
  applied = ref({ ...filters });
const profile = useData('/api/v1/ops/me');
function params() {
  return {
    ...applied.value,
    from: applied.value.from + 'T00:00:00+08:00',
    until: applied.value.until + 'T23:59:59.999+08:00',
    dimension: kind.value === 'intent' ? applied.value.dimension : 'all',
    granularity: 'day',
  };
}
const data = useData(() =>
  advanced.value
    ? ''
    : (kind.value === 'intent'
        ? '/api/v1/ops/reports/intents?'
        : kind.value === 'call'
          ? '/api/v1/ops/reports?'
          : '/api/v1/ops/benchmark/reports/' +
            ({ followUp: 'follow', textMsg: 'sms' }[kind.value] || kind.value) +
            '?') + query(params()),
);
const scripts = useData(() =>
    kind.value === 'intent' ? '/api/v1/workspace/scripts?size=200' : '',
  ),
  showRate = ref(true);
const error = ref(''),
  density = ref('default'),
  hidden = ref([]);
const columns = computed(() => {
  if (kind.value === 'call')
    return [
      { key: 'bucket', label: '日期' },
      ...[
        'attempts',
        'answered_attempts',
        'answer_rate',
        'telephony_seconds',
        'bill_seconds',
        'interested_sessions',
        'reference_cost',
        'statement_cost',
      ].map((key) => ({ key, label: reportLabels[key] })),
    ];
  const maps = {
    intent: {
      dimension_name: '坐席/任务',
      intent: '意向度',
      grade: '评级',
      total: '总量',
      ...(showRate.value ? { rate: '占比（%）' } : {}),
    },
    intercept: {
      date: '日期',
      outbound: '外呼数',
      blocked: '过滤拦截数',
      intercept_rate: '过滤拦截率（%）',
      reason: '拦截原因',
    },
    followUp: {
      agent: '坐席',
      agent_status: '在线状态',
      pending: '待跟进数',
      successful: '成功数',
      later: '稍后跟进',
      abandoned: '放弃',
    },
    textMsg: {
      date: '日期',
      task: '任务名称',
      sent: '发送数',
      successful: '成功数',
      failed: '失败数',
      success_rate: '成功率（%）',
    },
    recharge: {
      reference_no: '充值流水号',
      kind: '类型',
      signed_amount: '充值金额',
      currency: '币种',
      settlement_source: '充值方式',
      posted_at: '充值时间',
    },
  };
  return Object.entries(maps[kind.value] || {}).map(([key, label]) => ({ key, label }));
});
const rows = computed(() =>
  Array.isArray(data.data.value) ? data.data.value : data.data.value?.rows || [],
);
async function search() {
  if (!filters.from || !filters.until || filters.from > filters.until) {
    error.value = '请选择正确的日期范围';
    return;
  }
  error.value = '';
  applied.value = { ...filters };
  await data.load();
}
async function exportRows() {
  await perform(() =>
    ops('/exports', 'POST', {
      kind:
        kind.value === 'intent'
          ? 'intents'
          : kind.value === 'call'
            ? 'reports'
            : 'benchmark:' + ({ followUp: 'follow', textMsg: 'sms' }[kind.value] || kind.value),
      query: params(),
      request_key: crypto.randomUUID(),
    }),
  );
}
</script>
<template>
  <OperationsReports v-if="advanced" />
  <template v-else
    ><PageHeader
      :title="titles[kind] || '统计报表'"
      description="按选定时间查询明细并在消息中心下载导出结果"
      ><el-button v-if="profile.data.value?.can_export" @click="exportRows">导出</el-button
      ><el-button @click="data.load">刷新</el-button></PageHeader
    >
    <el-alert v-if="error || data.error.value" :title="error || data.error.value" type="error" />
    <el-card
      ><div class="toolbar">
        <el-input v-model="filters.from" type="date" aria-label="统计开始日期" /><el-input
          v-model="filters.until"
          type="date"
          aria-label="统计结束日期"
        /><el-select
          v-if="kind === 'call'"
          v-model="filters.actor_id"
          clearable
          aria-label="统计用户"
          ><el-option
            v-for="u in profile.data.value?.users || []"
            :key="u.id"
            :value="u.id"
            :label="u.full_name" /></el-select
        ><el-button type="primary" @click="search">查询</el-button
        ><el-select v-model="density" aria-label="表格行高"
          ><el-option label="紧凑" value="small" /><el-option
            label="标准"
            value="default" /><el-option label="宽松" value="large" /></el-select
        ><el-popover trigger="click" width="280"
          ><template #reference><el-button>列设置</el-button></template
          ><el-checkbox-group v-model="hidden"
            ><el-checkbox v-for="c in columns" :key="c.key" :value="c.key"
              >隐藏{{ c.label }}</el-checkbox
            ></el-checkbox-group
          ></el-popover
        >
      </div>
      <el-table :data="rows" :size="density" border
        ><el-table-column
          v-for="c in columns.filter((c) => !hidden.includes(c.key))"
          :key="c.key"
          :prop="c.key"
          :label="c.label"
          min-width="140"
          sortable
          ><template #default="{ row }">{{ row[c.key] ?? '—' }}</template></el-table-column
        ><el-table-column label="操作" width="120"
          ><template #default="{ row }"
            ><el-button
              link
              @click="
                $router.push({
                  path: kind === 'followUp' ? '/admin/operations/leads' : '/admin/tasks/record',
                  query:
                    kind === 'followUp'
                      ? { owner_id: row.id }
                      : { from: params().from, until: params().until },
                })
              "
              >详情</el-button
            ></template
          ></el-table-column
        ></el-table
      >
    </el-card></template
  >
</template>
