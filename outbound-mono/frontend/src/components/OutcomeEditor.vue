<script setup>
import { ref, computed, watch } from 'vue';
const props = defineProps({ event: Boolean, variables: { type: Array, default: () => [] } });
const rows = defineModel({ type: Array, default: () => [] });
const index = ref(-1),
  editing = ref(null),
  page = ref(1),
  dragging = ref(null),
  error = ref('');
const fields = {
  affirm: '肯定次数',
  deny: '否定次数',
  knowledge: '业务问题命中次数',
  rounds: '对话轮次',
  machine: '拦截语音助手',
  tags: '标签',
};
const ops = { eq: '等于', gte: '大于等于', lte: '小于等于', any: '包含任一标签' };
const type = computed(() => (props.event ? '事件' : '规则')),
  view = computed(() => rows.value.slice((page.value - 1) * 10, page.value * 10));
watch(
  () => rows.value.length,
  (n) => {
    page.value = Math.min(page.value, Math.max(1, Math.ceil(n / 10)));
  },
);
const condition = () => ({ field: 'rounds', op: 'gte', value: '1' });
function open(i = -1) {
  index.value = i;
  error.value = '';
  editing.value =
    i < 0
      ? {
          id: crypto.randomUUID(),
          conditions: [condition()],
          action: props.event ? 'tag' : 'grade',
          value: '',
          label: '',
          once: true,
        }
      : JSON.parse(JSON.stringify(rows.value[i]));
}
function save() {
  const r = editing.value;
  if (
    !r.value.trim() ||
    !r.conditions.length ||
    r.conditions.some((c) => !String(c.value).trim())
  ) {
    error.value = '请填写所有条件和动作值';
    return;
  }
  const list = [...rows.value];
  if (index.value < 0) list.push(r);
  else list[index.value] = r;
  rows.value = list;
  editing.value = null;
}
function move(from, to) {
  if (from == null || from === to || to < 0 || to >= rows.value.length) return;
  const list = [...rows.value];
  list.splice(to, 0, list.splice(from, 1)[0]);
  rows.value = list;
  dragging.value = null;
}
function remove(i) {
  rows.value = rows.value.filter((_, n) => n !== i);
}
const absolute = (i) => (page.value - 1) * 10 + i;
</script>
<template>
  <el-card :header="event ? '事件' : '挂机处理'">
    <p>
      {{
        event
          ? '每个客户回合按顺序判断，动作可写入本次通话的标签或评级。'
          : '挂机时按顺序判断，使用首条命中规则的动作。'
      }}
      拖动行号可调整优先顺序，保存草稿并发布后生效。
    </p>
    <el-button @click="open()">添加{{ type }}</el-button>
    <el-table :data="view" row-key="id" empty-text="暂无数据">
      <el-table-column label="序号" width="76"
        ><template #default="{ $index }"
          ><span
            draggable="true"
            :aria-label="'拖动' + type + (absolute($index) + 1)"
            @dragstart="dragging = absolute($index)"
            @dragover.prevent
            @drop.prevent="move(dragging, absolute($index))"
            >↕ {{ absolute($index) + 1 }}</span
          ></template
        ></el-table-column
      >
      <el-table-column :label="event ? '触发条件' : '规则'"
        ><template #default="{ row }">{{
          row.conditions.map((c) => fields[c.field] + ' ' + ops[c.op] + ' ' + c.value).join(' 且 ')
        }}</template></el-table-column
      >
      <el-table-column label="动作"
        ><template #default="{ row }"
          >{{
            { grade: '设置评级', tag: '添加标签', increment: '累加变量', set: '设置变量' }[
              row.action
            ]
          }}
          · {{ row.value }}</template
        ></el-table-column
      >
      <el-table-column prop="label" label="标签" /><el-table-column v-if="event" label="只执行一次"
        ><template #default="{ row }">{{ row.once ? '是' : '否' }}</template></el-table-column
      >
      <el-table-column label="操作" width="240"
        ><template #default="{ $index }"
          ><el-button link @click="open(absolute($index))">编辑{{ type }}</el-button
          ><el-button link @click="remove(absolute($index))">删除{{ type }}</el-button
          ><el-button
            link
            :disabled="!absolute($index)"
            @click="move(absolute($index), absolute($index) - 1)"
            >上移</el-button
          ></template
        ></el-table-column
      ></el-table
    >
    <el-pagination
      v-model:current-page="page"
      :page-size="10"
      :total="rows.length"
      layout="prev,pager,next,total"
    />
    <el-dialog :model-value="!!editing" @close="editing = null" :title="'配置' + type" width="700px"
      ><template v-if="editing">
        <div v-for="(c, i) in editing.conditions" :key="i" class="toolbar">
          <el-select
            v-model="c.field"
            :aria-label="'条件字段' + (i + 1)"
            @change="
              c.op = c.field === 'tags' ? 'any' : 'eq';
              c.value = c.field === 'tags' ? '' : '0';
            "
            ><el-option v-for="(v, k) in fields" :key="k" :label="v" :value="k" /><el-option
              v-for="v in variables.filter((v) => v.type === 'NUM')"
              :key="v.name"
              :label="'变量：' + v.name"
              :value="'variable:' + v.name"
          /></el-select>
          <el-select v-model="c.op" :aria-label="'比较方式' + (i + 1)"
            ><el-option
              v-for="k in c.field === 'tags' ? ['any'] : ['eq', 'gte', 'lte']"
              :key="k"
              :label="ops[k]"
              :value="k"
          /></el-select>
          <el-input
            v-model="c.value"
            :aria-label="'条件值' + (i + 1)"
            :placeholder="c.field === 'tags' ? '多个标签用逗号分隔' : '非负整数；是为1，否为0'"
          /><el-button v-if="editing.conditions.length > 1" @click="editing.conditions.splice(i, 1)"
            >移除条件</el-button
          >
        </div>
        <el-button
          :disabled="editing.conditions.length >= 12"
          @click="editing.conditions.push(condition())"
          >增加条件</el-button
        >
        <el-form label-position="top"
          ><el-form-item label="动作"
            ><el-select aria-label="规则动作" v-model="editing.action"
              ><el-option label="设置评级" value="grade" /><el-option
                label="添加标签"
                value="tag" /><el-option
                v-if="event"
                label="累加变量（变量名=数值）"
                value="increment" /><el-option
                v-if="event"
                label="设置变量（变量名=值）"
                value="set" /></el-select
          ></el-form-item>
          <el-form-item label="动作值"
            ><el-input
              v-model="editing.value"
              aria-label="规则动作值"
              maxlength="100" /></el-form-item
          ><el-form-item label="附加标签"
            ><el-input v-model="editing.label" aria-label="附加标签" maxlength="100"
          /></el-form-item>
          <el-checkbox v-if="event" v-model="editing.once">只执行一次</el-checkbox></el-form
        ><el-alert v-if="error" :title="error" type="error"
      /></template>
      <template #footer
        ><el-button @click="save">确定{{ type }}</el-button></template
      ></el-dialog
    >
  </el-card>
</template>
