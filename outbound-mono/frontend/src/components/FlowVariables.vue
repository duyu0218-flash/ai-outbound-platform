<script setup>
const rows = defineModel({ required: true });
</script>
<template>
  <el-card header="自定义变量"
    ><el-table :data="rows" border
      ><el-table-column label="变量名称"
        ><template #default="{ row }"
          ><el-input v-model="row.name" aria-label="变量名称" /></template></el-table-column
      ><el-table-column label="变量类型"
        ><template #default="{ row }"
          ><el-select
            v-model="row.type"
            aria-label="变量类型"
            @change="row.initial = row.type === 'NUM' ? 0 : row.type === 'BOOL' ? false : ''"
            ><el-option
              v-for="v in ['NUM', 'STR', 'BOOL']"
              :key="v"
              :value="v"
              :label="v" /></el-select></template></el-table-column
      ><el-table-column label="变量初始值"
        ><template #default="{ row }"
          ><el-input-number
            v-if="row.type === 'NUM'"
            v-model="row.initial"
            aria-label="变量初始值" /><el-switch
            v-else-if="row.type === 'BOOL'"
            v-model="row.initial"
            aria-label="变量初始值" /><el-input
            v-else
            v-model="row.initial"
            aria-label="变量初始值" /></template></el-table-column
      ><el-table-column label="是否导出"
        ><template #default="{ row }"
          ><el-switch v-model="row.export" aria-label="变量是否导出" /></template></el-table-column
      ><el-table-column label="操作"
        ><template #default="{ $index }"
          ><el-button link @click="rows.splice($index, 1)">移除变量</el-button></template
        ></el-table-column
      ></el-table
    ><el-button
      :disabled="rows.length >= 100"
      @click="rows.push({ name: '', type: 'NUM', initial: 0, export: false })"
      >添加自定义变量</el-button
    >
    <p>
      变量按每通电话初始化；事件支持设置变量和累加，写法为“变量名=值”。条件可引用“variable:变量名”。
    </p></el-card
  >
</template>
