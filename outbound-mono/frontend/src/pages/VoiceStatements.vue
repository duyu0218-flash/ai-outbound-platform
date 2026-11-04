<script setup>
import { ref } from 'vue';
import { useData } from '../data';
import { request } from '../session';
const feed = useData('/api/v1/ops/voice-statement'),
  busy = ref(false),
  error = ref(''),
  receipt = ref(null);
const headers =
  '流水号,类型,金额,通话ID,拨打序号,计费秒数,计费单位秒,每单位费率,费率版本,结算来源,入账时间UTC';
function template() {
  const u = URL.createObjectURL(
    new Blob(['\uFEFF' + headers + '\r\n'], { type: 'text/csv;charset=utf-8' }),
  );
  const a = document.createElement('a');
  a.href = u;
  a.download = '语音结算账单模板.csv';
  a.click();
  setTimeout(() => URL.revokeObjectURL(u), 1000);
}
async function load(e) {
  const file = e.target.files[0];
  if (!file) return;
  busy.value = true;
  receipt.value = null;
  try {
    const data = new FormData();
    data.append('file', file);
    receipt.value = await request('/api/v1/ops/voice-statement/import', 'POST', data);
    error.value = '';
    await feed.load();
  } catch (ex) {
    error.value = ex.message;
  } finally {
    busy.value = false;
    e.target.value = '';
  }
}
</script>
<template>
  <PageHeader title="语音结算账单" description="按本租户导入的对账明细核对话费、计费时长和余额。" />
  <el-alert v-if="error || feed.error.value" :title="error || feed.error.value" type="error" />
  <el-card
    ><h3>
      {{ feed.data.value?.balance.mode === 'simulation' ? '模拟账目' : '语音账目' }} ·
      {{
        feed.data.value?.balance.available ? feed.data.value.balance.amount + ' 元' : '尚无期初余额'
      }}
    </h3>
    <p>
      类型 opening 为期初余额，credit 为入账，charge 为通话扣费（负数），adjust
      为调整。按对账单原始金额、计费单位和费率版本填写。相同流水号重复导入会跳过，不同内容会拒绝整份文件。
    </p>
    <el-button @click="template">下载语音账单模板</el-button
    ><label
      >导入语音账单
      <input type="file" aria-label="导入语音账单" accept=".csv" :disabled="busy" @change="load"
    /></label>
    <p v-if="receipt">
      已入账 {{ receipt.imported }} 笔，重复 {{ receipt.duplicates }} 笔
    </p></el-card
  >
  <el-table :data="feed.data.value?.rows || []" row-key="reference_no"
    ><el-table-column
      v-for="[k, l] in [
        ['reference_no', '流水号'],
        ['kind', '类型'],
        ['signed_amount', '金额（元）'],
        ['call_id', '通话ID'],
        ['attempt', '拨打序号'],
        ['bill_seconds', '计费秒数'],
        ['unit_seconds', '计费单位秒'],
        ['unit_price', '每单位费率'],
        ['tariff_version', '费率版本'],
        ['settlement_source', '结算来源'],
        ['posted_at', '入账时间UTC'],
      ]"
      :key="k"
      :prop="k"
      :label="l"
      min-width="135"
  /></el-table>
</template>
