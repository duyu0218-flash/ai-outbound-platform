<script setup>
import { computed, ref } from 'vue';
import { useData } from '../data';
import { formatDate } from '../api';
const feed = useData('/api/v1/ops/seats/monitor', 15000);
const keyword = ref(''),
  scope = ref('全部坐席'),
  status = ref('全部状态'),
  layout = ref('表格'),
  expanded = ref(true);
const states = ['接管', '整理', '忙碌', '空闲', '离线'];
function current(s) {
  if (!Number(s.online)) return '离线';
  if (Number(s.taking_call)) return '接管';
  if (Number(s.wrapping)) return '整理';
  return s.agent_status === 'ready' ? '空闲' : '忙碌';
}
const all = computed(() => (feed.data.value || []).map((s) => ({ ...s, state: current(s) })));
const online = computed(() => all.value.filter((s) => s.state !== '离线').length);
const filtered = computed(() =>
  all.value.filter(
    (s) =>
      (scope.value === '全部坐席' || s.state !== '离线') &&
      (s.username + ' ' + s.full_name).toLowerCase().includes(keyword.value.trim().toLowerCase()) &&
      (status.value === '全部状态' || s.state === status.value),
  ),
);
function count(state) {
  return all.value.filter((s) => s.state === state).length;
}
function ratio(n, d) {
  return d ? ((n * 100) / d).toFixed(2) + '%' : '—';
}
</script>
<template>
  <el-card header="坐席状态统计"
    ><el-button @click="expanded = !expanded">{{
      expanded ? '收起坐席面板' : '展开坐席面板'
    }}</el-button>
    <template v-if="expanded"
      ><el-alert v-if="feed.error.value" :title="feed.error.value" type="error" />
      <p>
        坐席数 {{ all.length }} · 上线数 {{ online }} · 签入数
        {{ all.filter((s) => s.agent_status !== 'offline').length }}
      </p>
      <p>
        <span v-for="st in states" :key="st"
          >{{ st }} {{ count(st) }}（{{ ratio(count(st), all.length) }}）　</span
        >
      </p>
      <el-radio-group v-model="scope"
        ><el-radio-button value="全部坐席">全部坐席</el-radio-button
        ><el-radio-button value="在线坐席">在线坐席</el-radio-button></el-radio-group
      >
      <el-input
        v-model="keyword"
        clearable
        aria-label="搜索坐席"
        placeholder="搜索账号或姓名"
        style="max-width: 220px"
      />
      <el-select v-model="status" aria-label="筛选坐席状态" style="width: 140px"
        ><el-option v-for="s in ['全部状态', ...states]" :key="s" :value="s" :label="s"
      /></el-select>
      <el-button @click="feed.load()">刷新坐席</el-button
      ><el-button @click="layout = layout === '表格' ? '卡片' : '表格'"
        >切换到{{ layout === '表格' ? '卡片' : '表格' }}</el-button
      >
      <el-table v-if="layout === '表格'" :data="filtered" row-key="id"
        ><el-table-column prop="username" sortable label="坐席账号" /><el-table-column
          prop="state"
          label="当前状态"
        /><el-table-column
          prop="handling_seconds"
          sortable
          label="今日接管时长（秒）"
        /><el-table-column label="最后活动时间"
          ><template #default="{ row }">{{
            formatDate(row.last_seen_at)
          }}</template></el-table-column
        ></el-table
      >
      <template v-else
        ><el-card v-for="s in filtered" :key="s.id"
          ><strong>{{ s.username }} · {{ s.state }}</strong>
          <p>
            今日已接管 / 已分配 / 接管率：{{ s.accepted_today }} / {{ s.assigned_today }} /
            {{ ratio(s.accepted_today, s.assigned_today) }}
          </p>
          <small>最后活动时间 {{ formatDate(s.last_seen_at) }}</small></el-card
        ><el-empty v-if="!filtered.length" description="没有符合条件的坐席"
      /></template>
      <small>状态占比按全部启用坐席计算。今日接管统计按 UTC 自然日。</small></template
    ></el-card
  >
</template>
