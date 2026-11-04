<script setup>
import { ref, computed, onMounted } from 'vue';
import { request } from '../session';
const props = defineProps({ scriptId: [String, Number] }),
  day = 86400000;
function localInput(ms) {
  const d = new Date(ms);
  return new Date(ms - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}
function dates() {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return [localInput(+d), localInput(+d + day)];
}
const [start, end] = dates(),
  from = ref(start),
  until = ref(end),
  baseFrom = ref(localInput(+new Date(start) - day)),
  baseUntil = ref(start),
  period = ref('prev'),
  kind = ref('node'),
  data = ref(null),
  error = ref(''),
  busy = ref(false);
const modes = { prev: '环比上一天', week: '上周同一天', seven: '对比近7天', custom: '自定义' };
function params() {
  const a = +new Date(from.value),
    b = +new Date(until.value);
  const c =
    period.value === 'custom'
      ? +new Date(baseFrom.value)
      : a - (period.value === 'prev' ? day : 7 * day);
  const d =
    period.value === 'custom'
      ? +new Date(baseUntil.value)
      : period.value === 'seven'
        ? a
        : b - (period.value === 'prev' ? day : 7 * day);
  if (![a, b, c, d].every(Number.isFinite) || b <= a || d <= c)
    throw Error('请选择有效的开始和结束时间');
  return new URLSearchParams({
    kind: kind.value,
    from: new Date(a).toISOString(),
    until: new Date(b).toISOString(),
    compareFrom: new Date(c).toISOString(),
    compareUntil: new Date(d).toISOString(),
  });
}
let seq = 0;
async function load() {
  const id = ++seq;
  busy.value = true;
  try {
    const result = await request(
      '/api/v1/workspace/scripts/' + props.scriptId + '/reach?' + params(),
    );
    if (id === seq) {
      data.value = result;
      error.value = '';
    }
  } catch (e) {
    if (id === seq) {
      data.value = null;
      error.value = e.message;
    }
  } finally {
    if (id === seq) busy.value = false;
  }
}
async function toggle() {
  try {
    await request('/api/v1/workspace/scripts/' + props.scriptId + '/reach/settings', 'PUT', {
      enabled: !data.value.enabled,
      revision: data.value.revision,
    });
    await load();
  } catch (e) {
    error.value = e.message;
  }
}
async function reset() {
  [from.value, until.value] = dates();
  period.value = 'prev';
  kind.value = 'node';
  await load();
}
const ratio = (count, total) => (total ? Number(((100 * count) / total).toFixed(2)) : null);
const rows = computed(() => {
  if (!data.value) return [];
  const { current, comparison } = data.value,
    merged = new Map();
  for (const [k, list] of [
    ['count', current.rows],
    ['prior', comparison.rows],
  ])
    for (const item of list) {
      const id = item.flow_id + ':' + item.target_id;
      const row = merged.get(id) || {
        id,
        label: item.label,
        flow: item.flow_id,
        count: 0,
        prior: 0,
      };
      row[k] = Number(item.reached);
      merged.set(id, row);
    }
  return Array.from(merged.values(), (r) => {
    const rate = ratio(r.count, current.total),
      priorRate = ratio(r.prior, comparison.total);
    return {
      ...r,
      rate,
      priorRate,
      delta: rate === null || priorRate === null ? null : +(rate - priorRate).toFixed(2),
    };
  });
});
function exportCsv() {
  const cell = (v) =>
    '"' +
    String(v ?? '')
      .replace(/^[=+@\-\t\r]/, "'$&")
      .replaceAll('"', '""') +
    '"';
  const lines = [
    ['场景版本', '目标', '到达数', '到达率(%)', '对比到达数', '对比到达率(%)', '对比幅度(百分点)'],
    ...rows.value.map((r) => [r.flow, r.label, r.count, r.rate, r.prior, r.priorRate, r.delta]),
  ];
  const url = URL.createObjectURL(
    new Blob(['\uFEFF' + lines.map((l) => l.map(cell).join(',')).join('\r\n')], {
      type: 'text/csv;charset=utf-8',
    }),
  );
  const a = document.createElement('a');
  a.href = url;
  a.download = '节点与知识命中对比.csv';
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
onMounted(load);
</script>
<template>
  <el-card header="节点到达率"
    ><el-alert v-if="error" type="error" :title="error" />
    <p>
      按通话创建时间归属统计周期。同一通话重复到达同一节点只计一次；知识问答按进入匹配结果的通话数统计。分母为该时段已采集通话数，差值以百分点表示。
    </p>
    <div class="toolbar">
      <label>开始 <input type="datetime-local" v-model="from" aria-label="到达率开始" /></label
      ><label
        >结束（不含） <input type="datetime-local" v-model="until" aria-label="到达率结束"
      /></label>
      <el-button :disabled="!data || busy" @click="toggle">{{
        data?.enabled ? '关闭节点采集' : '开启节点采集'
      }}</el-button
      ><span>新采集随通话处理写入，刷新统计可见；关闭期间不补录。</span>
    </div>
    <el-radio-group v-model="period" aria-label="对比周期"
      ><el-radio-button v-for="(v, k) in modes" :key="k" :value="k">{{
        v
      }}</el-radio-button></el-radio-group
    >
    <template v-if="period === 'custom'"
      ><input aria-label="对比开始" type="datetime-local" v-model="baseFrom" /><input
        aria-label="对比结束"
        type="datetime-local"
        v-model="baseUntil"
    /></template>
    <el-button @click="load" :loading="busy">应用</el-button
    ><el-button @click="reset">重置</el-button
    ><el-button :disabled="!rows.length || busy" @click="exportCsv">导出对比</el-button>
    <el-radio-group v-model="kind" @change="load"
      ><el-radio-button value="node">场景-节点统计</el-radio-button
      ><el-radio-button value="knowledge">知识问答</el-radio-button></el-radio-group
    >
    <p>
      本期采集 {{ data?.current.total ?? '—' }} 通 · 对比期采集
      {{ data?.comparison.total ?? '—' }} 通
    </p>
    <el-table :data="rows" row-key="id" empty-text="暂无数据"
      ><el-table-column prop="flow" label="场景版本" /><el-table-column
        prop="label"
        :label="kind === 'node' ? '场景-节点' : '知识问答'"
      />
      <el-table-column
        v-for="[k, l] in [
          ['count', '到达数'],
          ['rate', '到达率（%）'],
          ['prior', '对比到达数'],
          ['priorRate', '对比到达率（%）'],
          ['delta', '对比幅度（百分点）'],
        ]"
        :key="k"
        sortable
        :prop="k"
        :label="l"
        ><template #default="{ row }">{{ row[k] ?? '—' }}</template></el-table-column
      ></el-table
    ></el-card
  >
</template>
