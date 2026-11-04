<script setup>
import { ref } from 'vue';
import { session } from '../session';
import { useData } from '../data';
import { ops, perform } from '../operations';
const emit = defineEmits(['close']),
  feed = useData('/api/v1/ops/support');
const title = ref(''),
  body = ref(''),
  reply = ref(''),
  selected = ref(null),
  busy = ref(false);
async function save(isReply) {
  if (busy.value) return;
  busy.value = true;
  try {
    const v = await perform(
      () =>
        isReply
          ? ops('/support/' + selected.value.object_id, 'PUT', {
              reply: reply.value,
              revision: selected.value.revision,
            })
          : ops('/support', 'POST', { title: title.value, body: body.value }),
      feed.load,
    );
    if (v) {
      selected.value = null;
      title.value = '';
      body.value = '';
      reply.value = '';
    }
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <el-drawer :model-value="true" title="客服工单" size="520px" @close="emit('close')"
    ><p>提交给本企业后台管理员处理，可在此查看回复。</p>
    <el-input
      aria-label="客服工单标题"
      v-model="title"
      placeholder="问题标题"
      maxlength="120"
    /><el-input
      aria-label="客服工单内容"
      type="textarea"
      v-model="body"
      placeholder="遇到了什么问题"
      maxlength="2000"
    /><el-button :disabled="busy || !title.trim() || !body.trim()" @click="save(false)"
      >提交工单</el-button
    ><el-alert v-if="feed.error.value" type="error" :title="feed.error.value" />
    <el-card v-for="r in feed.data.value || []" :key="r.object_id"
      ><strong>{{ r.name }}</strong>
      <p>{{ r.body }}</p>
      <p>{{ r.reply || '等待管理员回复' }}</p>
      <el-button
        v-if="session.user.role === 'admin'"
        @click="
          selected = r;
          reply = r.reply || '';
        "
        >处理工单</el-button
      ><template v-if="selected?.object_id === r.object_id"
        ><el-input aria-label="工单回复" v-model="reply" maxlength="2000" /><el-button
          :disabled="busy || !reply.trim()"
          @click="save(true)"
          >保存回复</el-button
        ></template
      ></el-card
    ></el-drawer
  >
</template>
