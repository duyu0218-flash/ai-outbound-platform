<script setup>
import { ref, reactive } from 'vue';
import { useData } from '../data';
import { query, ops, perform } from '../operations';
import OperationsCalls from './OperationsCalls.vue';
import CallDetails from '../components/CallDetails.vue';
const tab = ref('unmatched'),
  filters = reactive({
    phone: '',
    script_id: '',
    node: '',
    batch: '',
    stage: '',
    intent: '',
    state: 'pending',
    from: '',
    until: '',
    page: 1,
  }),
  applied = ref({ ...filters });
const data = useData(() => '/api/v1/ops/benchmark/inspection?' + query(applied.value)),
  scripts = useData('/api/v1/workspace/scripts?size=200'),
  selected = ref(null),
  error = ref('');
function search() {
  applied.value = { ...filters };
  for (const k of ['from', 'until'])
    if (applied.value[k]) applied.value[k] = new Date(applied.value[k]).toISOString();
  data.load();
}
async function adopt(row) {
  await perform(
    () => ops('/benchmark/inspection/' + row.id + '/adopt', 'POST', { revision: row.revision }),
    data.load,
  );
}
</script>
<template>
  <PageHeader
    title="AI-巡检"
    description="从未匹配问题定位话术覆盖盲区，采用建议后调试并发布"
  /><el-tabs v-model="tab"
    ><el-tab-pane label="未匹配巡检" name="unmatched" /><el-tab-pane
      label="评级巡检"
      name="ratings"
  /></el-tabs>
  <OperationsCalls v-if="tab === 'ratings'" />
  <template v-else
    ><el-alert
      v-if="error || data.error.value"
      :title="error || data.error.value"
      type="error"
    /><el-card
      ><el-form class="form-grid" label-position="top"
        ><el-form-item label="电话"
          ><el-input v-model="filters.phone" aria-label="巡检电话" /></el-form-item
        ><el-form-item label="话术"
          ><el-select v-model="filters.script_id" clearable aria-label="巡检话术"
            ><el-option
              v-for="s in scripts.data.value?.rows || []"
              :key="s.id"
              :label="s.name"
              :value="s.id" /></el-select></el-form-item
        ><el-form-item
          v-for="[key, label] in [
            ['node', '当前话术节点'],
            ['intent', '意向度'],
            ['batch', '批次号'],
            ['stage', '跟进状态'],
          ]"
          :key="key"
          :label="label"
          ><el-input v-model="filters[key]" :aria-label="'巡检' + label" /></el-form-item
        ><el-form-item label="开始时间"
          ><el-input
            v-model="filters.from"
            type="datetime-local"
            aria-label="巡检开始时间" /></el-form-item
        ><el-form-item label="结束时间"
          ><el-input
            v-model="filters.until"
            type="datetime-local"
            aria-label="巡检结束时间" /></el-form-item
      ></el-form>
      <div class="toolbar">
        <el-button type="primary" @click="search">查询</el-button
        ><el-button
          @click="
            Object.keys(filters).forEach(
              (k) => (filters[k] = k === 'page' ? 1 : k === 'state' ? 'pending' : ''),
            );
            search();
          "
          >重置</el-button
        ><el-select v-model="filters.state" aria-label="巡检状态"
          ><el-option
            v-for="[v, l] in [
              ['pending', '待处理'],
              ['adopted', '已采用'],
              ['dismissed', '已忽略'],
              ['all', '全部'],
            ]"
            :key="v"
            :value="v"
            :label="l" /></el-select
        ><el-button @click="data.load">刷新</el-button>
      </div></el-card
    >
    <el-card
      ><el-table :data="data.data.value?.rows || []" border
        ><el-table-column
          v-for="[key, label] in [
            ['created_at', '拨打时间'],
            ['phone', '电话'],
            ['stage', '跟进状态'],
            ['intent', '意向度'],
            ['question', '未匹配问题'],
            ['node', '当前话术节点'],
            ['customer_intent', '客户意图'],
            ['keywords', '建议关键词'],
            ['regex', '建议正则'],
            ['similar_reply', '相似回复'],
            ['batch', '批次号'],
          ]"
          :key="key"
          :prop="key"
          :label="label"
          min-width="150"
        /><el-table-column label="操作" width="210" fixed="right"
          ><template #default="{ row }"
            ><el-button link @click="adopt(row)">采用到关键词场景</el-button
            ><el-button link @click="selected = { id: row.call_id }">通话证据</el-button></template
          ></el-table-column
        ></el-table
      ><el-pagination
        :current-page="filters.page"
        :page-size="20"
        :total="data.data.value?.total || 0"
        layout="prev,pager,next,total"
        @current-change="
          filters.page = $event;
          search();
        "
      />
      <p>{{ data.data.value?.suggestion_source }}</p></el-card
    ></template
  >
  <CallDetails v-if="selected" :call="selected" role="admin" @close="selected = null" />
</template>
