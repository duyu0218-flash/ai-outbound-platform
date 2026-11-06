<script setup>
import { ref, watch } from 'vue';
import { useData } from '../data';
const props = defineProps({ modelValue: { type: Object, default: () => ({}) } }),
  emit = defineEmits(['update:modelValue']);
const model = ref(JSON.parse(JSON.stringify(props.modelValue))),
  assets = useData('/api/v1/admin/messaging/assets');
watch(
  () => props.modelValue,
  (v) => {
    if (JSON.stringify(v) !== JSON.stringify(model.value))
      model.value = JSON.parse(JSON.stringify(v || {}));
  },
  { deep: true },
);
watch(model, (v) => emit('update:modelValue', JSON.parse(JSON.stringify(v))), { deep: true });
function addCard() {
  model.value.cards ||= [];
  model.value.cards.push({ title: '', body: '', actions: [] });
}
function addAction(card) {
  card.actions ||= [];
  card.actions.push({
    code: 'button' + (card.actions.length + 1),
    label: '',
    type: 'url',
    value: '',
  });
}
function cleanAsset(card, value) {
  if (!value) delete card.asset_id;
  else card.asset_id = value;
}
</script>
<template>
  <div class="rich-editor">
    <h4>富媒体内容</h4>
    <el-input
      v-model="model.title"
      aria-label="富媒体标题"
      placeholder="标题（可选）"
      maxlength="128"
    /><el-select
      :model-value="model.asset_id"
      clearable
      placeholder="选择主素材"
      @update:model-value="(v) => cleanAsset(model, v)"
      ><el-option
        v-for="asset in (assets.data.value || []).filter((a) => a.state === 'READY')"
        :key="asset.id"
        :label="asset.name"
        :value="asset.id"
    /></el-select>
    <template v-for="(card, index) in [model, ...(model.cards || [])]" :key="index"
      ><el-card :header="index ? '轮播卡片 ' + index : '主卡片按钮'"
        ><template v-if="index"
          ><el-input v-model="card.title" placeholder="卡片标题" maxlength="128" /><el-input
            v-model="card.body"
            type="textarea"
            placeholder="卡片正文，支持双花括号变量"
            maxlength="1000"
          /><el-select
            :model-value="card.asset_id"
            clearable
            placeholder="选择卡片素材"
            @update:model-value="(v) => cleanAsset(card, v)"
            ><el-option
              v-for="asset in (assets.data.value || []).filter((a) => a.state === 'READY')"
              :key="asset.id"
              :label="asset.name"
              :value="asset.id" /></el-select
          ><el-button
            link
            @click="
              model.cards.splice(index - 1, 1);
              if (!model.cards.length) delete model.cards;
            "
            >移除卡片</el-button
          ></template
        >
        <div v-for="(action, n) in card.actions || []" :key="n" class="action">
          <el-input v-model="action.code" placeholder="按钮编号" /><el-input
            v-model="action.label"
            placeholder="按钮文案"
            maxlength="32"
          /><el-select v-model="action.type"
            ><el-option value="url" label="打开链接" /><el-option
              value="phone"
              label="拨打电话" /><el-option value="reply" label="快捷回复" /></el-select
          ><el-input
            v-model="action.value"
            :placeholder="
              action.type === 'url'
                ? 'https://链接地址'
                : action.type === 'phone'
                  ? '电话号码'
                  : '回复内容'
            "
          /><el-button link @click="card.actions.splice(n, 1)">移除</el-button>
        </div>
        <el-button :disabled="(card.actions?.length || 0) >= 5" @click="addAction(card)"
          >添加按钮</el-button
        ></el-card
      ></template
    ><el-button :disabled="(model.cards?.length || 0) >= 10" @click="addCard"
      >添加轮播卡片</el-button
    >
    <p>素材和按钮目标须完成供应商审核，最终显示效果以终端验收为准。</p>
  </div>
</template>
<style scoped>
.el-input,
.el-select {
  margin: 6px 0;
}
.el-card {
  margin: 12px 0;
}
.action {
  border-bottom: 1px solid #eee;
  padding-bottom: 10px;
}
.rich-editor {
  margin: 16px 0;
}
</style>
