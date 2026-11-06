<script setup>
import { ref } from 'vue';
import { request, download, session } from '../session';
import { useData } from '../data';
const props = defineProps({
    scriptId: [String, Number],
    templates: Array,
    products: Array,
    senders: Array,
  }),
  rows = defineModel({ required: true }),
  open = ref(false),
  model = ref({}),
  parameters = ref('{}'),
  index = ref(-1),
  error = ref(''),
  people = useData('/api/v1/ops/me');
function edit(row, i = -1) {
  index.value = i;
  model.value = row
    ? JSON.parse(JSON.stringify(row))
    : {
        title: '',
        product_id: '',
        sender_id: '',
        template_id: '',
        contact_id: null,
        owner_id: session.user.id,
        auto_send: false,
        variables: {},
      };
  parameters.value = JSON.stringify(model.value.variables, null, 2);
  error.value = '';
  open.value = true;
}
function save() {
  try {
    model.value.variables = JSON.parse(parameters.value);
    if (!model.value.title || !model.value.template_id || !model.value.contact_id)
      throw Error('请填写标题、模板和授权联系人');
    if (index.value < 0) rows.value.push(model.value);
    else rows.value[index.value] = model.value;
    open.value = false;
  } catch (e) {
    error.value = e.message;
  }
}
async function upload(event) {
  const file = event.target.files?.[0];
  if (!file) return;
  const data = new FormData();
  data.append('file', file);
  try {
    const v = await request(
      '/api/v1/workspace/scripts/' + props.scriptId + '/notifications/import',
      'POST',
      data,
    );
    if (rows.value.length + v.rows.length > 100) throw Error('通知短信最多100条');
    rows.value.push(...v.rows);
  } catch (e) {
    error.value = e.message;
  }
  event.target.value = '';
}
</script>
<template>
  <el-card header="通知短信"
    ><el-alert v-if="error" :title="error" type="error" />
    <div class="toolbar">
      <el-button :disabled="rows.length >= 100" @click="edit()">添加通知短信</el-button
      ><template v-if="scriptId"
        ><label
          >上传短信<input
            type="file"
            accept=".xlsx,.csv"
            aria-label="上传通知短信"
            @change="upload" /></label
        ><el-button
          @click="
            download(
              '/api/v1/workspace/scripts/' + scriptId + '/notifications/template',
              '通知短信模板.xlsx',
            )
          "
          >excel模板</el-button
        ><el-button
          @click="
            download(
              '/api/v1/workspace/scripts/' + scriptId + '/notifications/export',
              '通知短信.xlsx',
              { method: 'POST', body: { rows } },
            )
          "
          >全部导出</el-button
        ></template
      >
    </div>
    <el-table :data="rows" border
      ><el-table-column prop="title" label="标题" /><el-table-column label="短信通道"
        ><template #default="{ row }">{{
          products?.find((p) => p.id === row.product_id)?.name || '继承默认通道'
        }}</template></el-table-column
      ><el-table-column label="参数"
        ><template #default="{ row }">{{
          JSON.stringify(row.variables)
        }}</template></el-table-column
      ><el-table-column label="所属"
        ><template #default="{ row }">{{
          people.data.value?.users?.find((u) => u.id === row.owner_id)?.full_name || row.owner_id
        }}</template></el-table-column
      ><el-table-column label="自动发送"
        ><template #default="{ row }">{{ row.auto_send ? '是' : '否' }}</template></el-table-column
      ><el-table-column label="操作"
        ><template #default="{ row, $index }"
          ><el-button link @click="edit(row, $index)">编辑通知</el-button
          ><el-button link @click="rows.splice($index, 1)">删除通知</el-button></template
        ></el-table-column
      ></el-table
    >
    <p>
      应用到话术草稿、发布并在任务确认短信预算后生效。自动发送按每次通话与每条通知分别去重。
    </p></el-card
  ><el-dialog v-model="open" title="通知短信配置" width="650px" destroy-on-close
    ><el-form label-position="top"
      ><el-form-item label="标题"
        ><el-input v-model="model.title" aria-label="通知短信标题" maxlength="160" /></el-form-item
      ><el-form-item
        v-for="[key, label, values] in [
          ['product_id', '短信通道', products],
          ['sender_id', '短信签名', senders],
          ['template_id', '短信模板', templates],
        ]"
        :key="key"
        :label="label"
        ><el-select v-model="model[key]" :aria-label="'通知' + label" clearable
          ><el-option
            v-for="r in values || []"
            :key="r.id"
            :value="r.id"
            :label="r.name" /></el-select></el-form-item
      ><el-form-item label="已授权通知联系人编号"
        ><el-input-number
          v-model="model.contact_id"
          :min="1"
          aria-label="通知联系人" /></el-form-item
      ><el-form-item label="短信所属"
        ><el-select v-model="model.owner_id" aria-label="通知短信所属"
          ><el-option
            v-for="u in people.data.value?.users || []"
            :key="u.id"
            :value="u.id"
            :label="u.full_name" /></el-select></el-form-item
      ><el-form-item label="参数内容（JSON对象）"
        ><el-input v-model="parameters" type="textarea" aria-label="通知参数内容" /></el-form-item
      ><el-form-item label="自动发送"
        ><el-switch v-model="model.auto_send" aria-label="通知自动发送" /></el-form-item
      ><el-button @click="open = false">取消</el-button
      ><el-button type="primary" @click="save">添加通知</el-button></el-form
    ></el-dialog
  >
</template>
