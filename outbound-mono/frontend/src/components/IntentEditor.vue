<script setup>
import { ref, computed } from 'vue';
const rules = defineModel({ type: Array, default: () => [] }),
  selected = ref([]),
  editIndex = ref(-1),
  editing = ref(null),
  err = ref('');
const targets = {
  affirm: '肯定',
  deny: '否定',
  rejected: '拒绝',
  continue: '全部',
  wait: '在忙',
  machine_candidate: '助手',
  interested: '有意向',
  question: '提问',
};
const presets = [
  ['肯定', 'affirm', '好啊；可以的', ''],
  ['否定', 'deny', '并非如此；不可以', ''],
  ['拒绝', 'rejected', '暂时用不到；谢谢不用', ''],
  ['在忙', 'wait', '正在开会；在开车', ''],
  ['助手', 'machine_candidate', '请在提示音后留言', ''],
  ['全部', 'continue', '', '.*'],
].map(([name, target, keywords, regex]) => ({ name, target, keywords, regex }));
const missing = computed(() => presets.filter((p) => !rules.value?.some((r) => r.name === p.name)));
function open(i = -1) {
  editIndex.value = i;
  err.value = '';
  editing.value =
    i < 0 ? { name: '', target: 'affirm', keywords: '', regex: '' } : { ...rules.value[i] };
}
function save() {
  const value = { ...editing.value, name: editing.value.name.trim() };
  if (
    !value.name ||
    (!value.keywords.trim() && !value.regex.trim()) ||
    rules.value.some((r, i) => i !== editIndex.value && r.name === value.name)
  ) {
    err.value = '名称必须唯一，且至少填写关键词或正则';
    return;
  }
  const list = [...rules.value];
  if (editIndex.value < 0) {
    const fallback = list.findIndex((r) => r.regex === '.*');
    list.splice(fallback < 0 ? list.length : fallback, 0, value);
  } else list[editIndex.value] = value;
  rules.value = list;
  editing.value = null;
}
function remove(names) {
  rules.value = rules.value.filter((r) => !names.includes(r.name));
  selected.value = [];
}
function sync() {
  const additions = missing.value.map((r) => ({ ...r }));
  const list = [...(rules.value || [])];
  const fallback = list.findIndex((r) => r.regex === '.*');
  list.splice(fallback < 0 ? list.length : fallback, 0, ...additions);
  rules.value = list;
}
</script>
<template>
  <el-card header="意图管理"
    ><p>
      按列表顺序匹配。停止联系、明确拒绝等基础规则优先。修改后保存草稿并发布，新任务引用发布版本。
    </p>
    <el-button @click="open()">添加意图</el-button
    ><el-button :disabled="!missing.length" @click="sync"
      >同步内置意图（{{ missing.length }}）</el-button
    ><el-button :disabled="!selected.length" @click="remove(selected.map((r) => r.name))"
      >删除选中意图</el-button
    ><el-table :data="rules" row-key="name" @selection-change="selected = $event"
      ><el-table-column type="selection" /><el-table-column
        prop="name"
        label="名称"
      /><el-table-column prop="keywords" label="关键词" /><el-table-column
        prop="regex"
        label="正则表达式"
      /><el-table-column label="识别结果"
        ><template #default="{ row }">{{ targets[row.target] }}</template></el-table-column
      ><el-table-column label="操作"
        ><template #default="{ row, $index }"
          ><el-button link @click="open($index)">编辑意图</el-button
          ><el-button link @click="remove([row.name])">删除意图</el-button></template
        ></el-table-column
      ></el-table
    ></el-card
  >
  <el-dialog :model-value="!!editing" @close="editing = null" title="编辑意图"
    ><el-form v-if="editing" label-position="top"
      ><el-form-item label="名称"
        ><el-input v-model="editing.name" aria-label="意图名称" maxlength="80" /></el-form-item
      ><el-form-item label="识别结果"
        ><el-select aria-label="意图结果" v-model="editing.target"
          ><el-option
            v-for="(v, k) in targets"
            :key="k"
            :label="v"
            :value="k" /></el-select></el-form-item
      ><el-form-item label="关键词（分号分隔）"
        ><el-input
          v-model="editing.keywords"
          aria-label="意图关键词"
          maxlength="4000" /></el-form-item
      ><el-form-item label="正则表达式"
        ><el-input v-model="editing.regex" aria-label="意图正则" maxlength="256" /></el-form-item
      ><el-alert v-if="err" type="error" :title="err" /></el-form
    ><template #footer><el-button @click="save">确定意图</el-button></template></el-dialog
  >
</template>
