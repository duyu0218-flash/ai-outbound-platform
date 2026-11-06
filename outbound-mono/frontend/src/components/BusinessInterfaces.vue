<script setup>
import { ref } from 'vue';
const rows = defineModel({ required: true }),
  open = ref(false),
  model = ref(null),
  index = ref(-1),
  body = ref('{}'),
  bindings = ref('{}'),
  error = ref('');
function edit(row, i = -1) {
  index.value = i;
  model.value = row
    ? JSON.parse(JSON.stringify(row))
    : {
        id: crypto.randomUUID(),
        name: '',
        url: '',
        method: 'POST',
        content_type: 'application/json',
        body: {},
        response_variables: {},
      };
  body.value = JSON.stringify(model.value.body, null, 2);
  bindings.value = JSON.stringify(model.value.response_variables, null, 2);
  error.value = '';
  open.value = true;
}
function save() {
  try {
    model.value.body = JSON.parse(body.value);
    model.value.response_variables = JSON.parse(bindings.value);
    if (!model.value.name.trim() || !model.value.url.startsWith('https://'))
      throw Error('请填写名称和HTTPS地址');
    if (index.value < 0) rows.value.push(model.value);
    else rows.value[index.value] = model.value;
    open.value = false;
  } catch (e) {
    error.value = e.message;
  }
}
</script>
<template>
  <el-card header="调用接口"
    ><el-button @click="edit()">增加接口</el-button
    ><el-table :data="rows" border
      ><el-table-column prop="name" label="名称" /><el-table-column
        prop="method"
        label="方法"
      /><el-table-column prop="url" label="接口地址" /><el-table-column label="接口内容"
        ><template #default="{ row }">{{ JSON.stringify(row.body) }}</template></el-table-column
      ><el-table-column prop="content_type" label="内容类型" /><el-table-column label="配置变量"
        ><template #default="{ row }">{{
          JSON.stringify(row.response_variables)
        }}</template></el-table-column
      ><el-table-column label="操作"
        ><template #default="{ row, $index }"
          ><el-button link @click="edit(row, $index)">编辑接口</el-button
          ><el-button link @click="rows.splice($index, 1)">删除接口</el-button></template
        ></el-table-column
      ></el-table
    >
    <p>
      在流程画布节点选择接口。每通电话每个节点调用一次，响应写入变量后继续流程；失败时礼貌结束。请求只支持HTTPS公网JSON接口，最长5秒。请求中可用“{变量名}”引用变量，响应映射格式为“变量名:
      JSON字段路径”。
    </p></el-card
  ><el-dialog v-model="open" title="增加接口" width="640px"
    ><el-alert v-if="error" type="error" :title="error" /><el-form v-if="model" label-position="top"
      ><el-form-item label="名称"
        ><el-input v-model="model.name" aria-label="接口名称" /></el-form-item
      ><el-form-item label="接口地址"
        ><el-input v-model="model.url" aria-label="接口地址" /></el-form-item
      ><el-form-item label="方法">POST</el-form-item
      ><el-form-item label="接口内容（JSON对象）"
        ><el-input v-model="body" type="textarea" aria-label="接口内容" /></el-form-item
      ><el-form-item label="内容类型">application/json</el-form-item
      ><el-form-item label="配置变量（JSON对象）"
        ><el-input v-model="bindings" type="textarea" aria-label="接口配置变量" /></el-form-item
      ><el-button @click="open = false">取消</el-button
      ><el-button type="primary" @click="save">确定接口</el-button></el-form
    ></el-dialog
  >
</template>
