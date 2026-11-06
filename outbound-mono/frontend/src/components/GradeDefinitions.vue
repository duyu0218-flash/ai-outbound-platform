<script setup>
const rows = defineModel({ required: true });
const defaults = () =>
  [
    [11, 'A成功结束'],
    [9, 'B有意向'],
    [7, 'C待跟进'],
    [5, 'D在忙'],
    [3, 'E语音助手'],
    [2, 'E接通挂机'],
    [1, 'F直接挂机'],
    [0, 'G'],
  ].map(([score, name]) => ({ score, name }));
</script>
<template>
  <el-card header="自定义评级"
    ><el-button @click="rows = defaults()">载入八级评级</el-button
    ><el-table :data="rows" border
      ><el-table-column label="评级分值"
        ><template #default="{ row }"
          ><el-input-number
            v-model="row.score"
            :min="0"
            :max="100"
            aria-label="评级分值" /></template></el-table-column
      ><el-table-column label="评级名称"
        ><template #default="{ row }"
          ><el-input
            v-model="row.name"
            maxlength="100"
            aria-label="评级名称" /></template></el-table-column
      ><el-table-column label="操作"
        ><template #default="{ $index }"
          ><el-button link @click="rows.splice($index, 1)">删除评级</el-button></template
        ></el-table-column
      ></el-table
    ><el-button :disabled="rows.length >= 100" @click="rows.push({ score: 0, name: '' })"
      >添加评级</el-button
    >
    <p>挂机规则中使用对应评级名称，话单同时记录名称和分值。</p></el-card
  >
</template>
