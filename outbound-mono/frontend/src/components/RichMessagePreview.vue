<script setup>
defineProps({ rich: { type: Object, default: () => ({}) } });
</script>
<template>
  <div class="rich-preview">
    <template v-for="(card, index) in [rich, ...(rich.cards || [])]" :key="index">
      <el-card v-if="card.title || card.body || card.asset_id || card.actions?.length">
        <h4 v-if="card.title">{{ card.title }}</h4>
        <p v-if="card.body" style="white-space: pre-wrap">{{ card.body }}</p>
        <small v-if="card.asset_id">媒体素材：{{ card.asset_id }}</small>
        <p v-for="action in card.actions || []" :key="action.code">
          <el-tag
            >{{ action.label }} ·
            {{ { url: '打开链接', phone: '拨打电话', reply: '快捷回复' }[action.type] }}</el-tag
          >
          {{ action.value }}
        </p>
      </el-card>
    </template>
  </div>
</template>
<style scoped>
.el-card {
  margin: 10px 0;
}
small {
  overflow-wrap: anywhere;
}
</style>
