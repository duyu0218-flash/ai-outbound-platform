<script setup>
import { computed, ref } from 'vue';
import { useData } from '../data';
import { ops, perform } from '../operations';
import { ElMessageBox } from 'element-plus';
import { formatDate } from '../api';
const feed = useData('/api/v1/ops/seats/monitor', 15000);
const keyword = ref(''),
  scope = ref('全部坐席'),
  status = ref('全部状态'),
  layout = ref('表格'),
  expanded = ref(true);
const users = useData('/api/v1/admin/users?page=1&size=200', 0);
const chosen = ref(null),
  cap = ref(0),
  multiple = ref(2),
  info = ref(null),
  filterOpen = ref(true);
async function getCap() {
  const id = chosen.value;
  info.value = null;
  const v = await perform(() => ops('/users/' + id + '/dial-limit'));
  if (v && chosen.value === id) {
    info.value = v;
    cap.value = v.limit;
  }
}
async function saveCap(value) {
  const id = chosen.value;
  if (!info.value || !Number.isInteger(value) || value < 0 || value > 100000) return;
  const v = await perform(() =>
    ops('/users/' + id + '/dial-limit', 'PUT', { limit: value, revision: info.value.revision }),
  );
  if (v && chosen.value === id) {
    info.value = v;
    cap.value = v.limit;
  }
}
async function offline() {
  try {
    await ElMessageBox.confirm(
      '当前租户全部空闲及非通话坐席将下线，正在通话的坐席会保留。',
      '全部下线',
    );
  } catch {
    return;
  }
  await perform(() => ops('/seats/offline', 'POST', { confirmed: true }), feed.load);
}
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
      <el-button
        aria-label="筛选坐席"
        @click="
          filterOpen = !filterOpen;
          if (!filterOpen) {
            keyword = '';
            status = '全部状态';
          }
        "
        >筛选</el-button
      ><el-input
        v-if="filterOpen"
        v-model="keyword"
        clearable
        aria-label="搜索坐席"
        placeholder="搜索账号或姓名"
        style="max-width: 220px"
      />
      <el-select v-if="filterOpen" v-model="status" aria-label="筛选坐席状态" style="width: 140px"
        ><el-option v-for="s in ['全部状态', ...states]" :key="s" :value="s" :label="s"
      /></el-select>
      <el-button @click="feed.load()">刷新坐席</el-button
      ><el-button @click="layout = layout === '表格' ? '卡片' : '表格'"
        >切换到{{ layout === '表格' ? '卡片' : '表格' }}</el-button
      >
      <p><el-button type="danger" @click="offline">全部下线</el-button></p>
      <div>
        用户
        <el-select style="width: 200px" aria-label="并发用户" v-model="chosen" @change="getCap"
          ><el-option
            v-for="u in Array.isArray(users.data.value)
              ? users.data.value
              : users.data.value?.items || []"
            :key="u.id"
            :value="u.id"
            :label="u.username"
        /></el-select>
        并发数量
        <el-input-number
          v-model="cap"
          aria-label="并发数量"
          :min="0"
          :max="100000"
          :precision="0"
        /><el-button :disabled="!info" @click="saveCap(cap)">修改并发</el-button> 倍数
        <el-input-number
          v-model="multiple"
          aria-label="并发倍数"
          :min="0.1"
          :max="100"
          :step="0.5"
        /><el-button
          :disabled="!info || info.inherited || Math.floor(info.limit * multiple) > 100000"
          @click="saveCap(Math.floor(info.limit * multiple))"
          >倍速修改</el-button
        ><small v-if="info">{{
          info.inherited
            ? '继承租户并发上限'
            : '已设上限 ' + info.limit + '，倍速调整后 ' + Math.floor(info.limit * multiple)
        }}</small>
      </div>
      <el-table v-if="layout === '表格'" :data="filtered" row-key="id"
        ><el-table-column prop="username" sortable label="坐席账号" /><el-table-column
          prop="state"
          label="当前状态"
        /><el-table-column
          prop="idle_seconds"
          sortable
          label="今日空闲时长（秒）"
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
