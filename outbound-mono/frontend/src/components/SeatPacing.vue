<script setup>
import { ref } from 'vue';
import { useData } from '../data';
import { ops, perform } from '../operations';
const list = useData('/api/v1/ops/seats/pacing', 15000),
  teams = useData('/api/v1/ops/teams');
const open = ref(false),
  team = ref(null),
  revision = ref(0),
  model = ref({ multiplier: 0, average_wait_seconds: 5 });
function select() {
  const row = list.data.value?.find((r) => String(r.object_id) === String(team.value));
  revision.value = row?.revision || 0;
  model.value = row ? structuredClone(row.draft) : { multiplier: 0, average_wait_seconds: 5 };
}
async function save() {
  const v = await perform(
    () =>
      ops('/seats/pacing/' + team.value, 'PUT', { revision: revision.value, config: model.value }),
    list.load,
  );
  if (v) open.value = false;
}
</script>
<template>
  <el-button @click="open = true">倍速修改</el-button
  ><el-table :data="list.data.value || []"
    ><el-table-column prop="name" label="坐席组" /><el-table-column
      prop="signed_in"
      label="签入坐席数" /><el-table-column
      prop="draft.multiplier"
      label="倍速值" /><el-table-column
      prop="maximum_concurrency"
      label="坐席组可用最大并发" /><el-table-column
      prop="draft.average_wait_seconds"
      label="平均等待时长上限（秒）" /></el-table
  ><el-dialog v-model="open" title="倍速修改" width="520px"
    ><p>签入坐席数 × 倍速值 = 坐席组可用最大并发。0表示不启用倍速。</p>
    <el-form label-position="top"
      ><el-form-item label="坐席组" required
        ><el-select v-model="team" aria-label="倍速坐席组" @change="select"
          ><el-option
            v-for="t in teams.data.value || []"
            :key="t.id"
            :value="t.id"
            :label="t.name" /></el-select></el-form-item
      ><el-form-item label="倍速值" required
        ><el-input-number
          v-model="model.multiplier"
          :min="0"
          :max="100"
          :precision="2"
          aria-label="坐席组倍速值" /></el-form-item
      ><el-form-item label="平均等待时长秒" required
        ><el-input-number
          v-model="model.average_wait_seconds"
          :min="0"
          :max="300"
          aria-label="倍速平均等待时长" /></el-form-item
      ><el-button type="primary" :disabled="!team" @click="save">确定</el-button></el-form
    ></el-dialog
  >
</template>
