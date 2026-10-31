<script setup>
import { ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { clientRequest } from './session';
import { reasonLabels } from './labels';
const days = ref(30),
  stats = ref(null);
async function load() {
  try {
    stats.value = await clientRequest('/api/v1/client/stats?days=' + days.value);
  } catch (e) {
    ElMessage.error(e.message);
  }
}
watch(days, load, { immediate: true });
const max = (rows) => Math.max(1, ...rows.map((r) => Number(r.delivered)));
</script>
<template>
  <div v-if="stats">
    <div class="actions">
      <el-radio-group v-model="days"
        ><el-radio-button :value="7">近 7 天</el-radio-button
        ><el-radio-button :value="30">近 30 天</el-radio-button
        ><el-radio-button :value="90">近 90 天</el-radio-button></el-radio-group
      >
    </div>
    <div class="metric-row">
      <el-card class="metric-card"
        ><div class="muted">累计交付</div>
        <strong>{{ stats.summary.total }}</strong></el-card
      >
      <el-card class="metric-card"
        ><div class="muted">有效率</div>
        <strong>{{ stats.valid_rate == null ? '-' : stats.valid_rate + '%' }}</strong></el-card
      >
      <el-card class="metric-card"
        ><div class="muted">已成交</div>
        <strong>{{ stats.summary.won }}</strong></el-card
      >
      <el-card class="metric-card"
        ><div class="muted">申诉中</div>
        <strong>{{ stats.summary.disputing }}</strong></el-card
      >
    </div>
    <el-card>
      <h4>每日交付</h4>
      <div v-if="!stats.trend.length" class="muted">所选时间内暂无交付</div>
      <div v-for="row in stats.trend" :key="row.day" class="bar-row">
        <span class="bar-label">{{ String(row.day).slice(5, 10) }}</span>
        <span
          class="bar"
          :style="{ width: (Number(row.delivered) / max(stats.trend)) * 70 + '%' }"
        ></span>
        <span>{{ row.delivered }}（有效 {{ row.valid }} / 无效 {{ row.invalid }}）</span>
      </div>
    </el-card>
    <el-card>
      <h4>按来源任务</h4>
      <el-table :data="stats.campaigns" stripe
        ><el-table-column prop="campaign_name" label="任务" /><el-table-column
          prop="delivered"
          label="交付" /><el-table-column prop="valid" label="有效" /><el-table-column
          prop="invalid"
          label="无效" /><el-table-column prop="disputes_accepted" label="申诉成立"
      /></el-table>
    </el-card>
    <el-card>
      <h4>无效原因分布</h4>
      <el-table :data="stats.invalid_reasons" stripe
        ><el-table-column label="原因"
          ><template #default="{ row }">{{
            reasonLabels[row.reason] || '未填写'
          }}</template></el-table-column
        ><el-table-column prop="total" label="数量"
      /></el-table>
    </el-card>
  </div>
</template>
