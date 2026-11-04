<script setup>
import { ref, computed } from 'vue';
import { useData } from '../data';
import { session } from '../session';
const props = defineProps({ section: String }),
  policy = defineModel({ required: true }),
  emit = defineEmits(['section']);
const lines = useData('/api/v1/workspace/task-resources/selectable-lines'),
  profiles = useData(session.user.role === 'admin' ? '/api/v1/admin/voice-ai/profiles' : '');
const n = ref(''),
  error = ref(''),
  op = ref(''),
  msg = ref('');
const def = {
  blocked_hashes: [],
  openings: [],
  service_combo: 'inherit',
  callback_mode: 'inherit',
};
const b = computed(() => policy.value.bindings || def);
function update(k, v) {
  policy.value.bindings = { ...b.value, [k]: v };
}
async function fingerprint(v) {
  const s = v.replace(/[\s()+-]/g, '');
  if (!/^\d{6,15}$/.test(s)) throw Error('请输入有效号码');
  const bytes = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)));
  return Array.from(bytes, (x) => x.toString(16).padStart(2, '0')).join('');
}
async function block(remove) {
  try {
    const hash = await fingerprint(n.value),
      set = new Set(b.value.blocked_hashes);
    if (remove) set.delete(hash);
    else set.add(hash);
    update('blocked_hashes', [...set]);
    n.value = '';
    msg.value = remove ? '已移出草稿黑名单' : '已加入草稿黑名单';
    error.value = '';
  } catch (e) {
    error.value = e.message;
  }
}
function addOpening() {
  if (
    op.value.trim() &&
    !b.value.openings.includes(op.value.trim()) &&
    b.value.openings.length < 20
  )
    update('openings', [...b.value.openings, op.value.trim()]);
  op.value = '';
}
</script>
<template>
  <el-card :header="section"
    ><p>保存草稿并发布版本后，新的任务可引用这些设置。</p>
    <template v-if="section === '手拨线路'"
      ><label
        >话术默认线路<select
          aria-label="话术默认线路"
          :value="b.line_id || 0"
          @change="update('line_id', Number($event.target.value))"
        >
          <option :value="0">由任务选择</option>
          <option v-for="r in lines.data.value || []" :key="r.id" :value="r.id">
            {{ r.name }}
          </option>
        </select></label
      >
      <p>建任务时带入默认线路，运营可以改选。</p></template
    >
    <template v-else-if="section === '黑名单'"
      ><el-input
        v-model="n"
        aria-label="话术禁呼号码"
        placeholder="填写要加入或移出的号码" /><el-button @click="block(false)"
        >加入黑名单</el-button
      ><el-button @click="block(true)">移出黑名单</el-button>
      <p>版本黑名单 {{ b.blocked_hashes.length }} 个号码。{{ msg }}</p>
      <el-alert v-if="error" :title="error" type="error"
    /></template>
    <template v-else-if="section === '多开场白设置'"
      ><el-input v-model="op" maxlength="240" aria-label="备用开场白" /><el-button
        @click="addOpening"
        >添加开场白</el-button
      >
      <p v-for="(v, i) in b.openings" :key="v">
        {{ i + 1 }}. {{ v }}
        <el-button
          link
          @click="
            update(
              'openings',
              b.openings.filter((x) => x !== v),
            )
          "
          >移除</el-button
        >
      </p>
      <p>每次拨打按会话及尝试编号选定一句，同一尝试重试沿用该句。</p></template
    >
    <template v-else-if="section === '接口配置'"
      ><label
        >业务回调<select
          aria-label="话术接口策略"
          :value="b.callback_mode"
          @change="update('callback_mode', $event.target.value)"
        >
          <option value="inherit">沿用租户已授权接口</option>
          <option value="off">关闭此话术的业务回调</option>
        </select></label
      ></template
    >
    <template v-else
      ><p>语音合成和识别使用同一服务组合。在任务编辑时可覆盖话术默认值。</p>
      <label
        >服务组合<select
          aria-label="话术语音组合"
          :value="b.service_combo"
          @change="update('service_combo', $event.target.value)"
        >
          <option value="inherit">沿用租户设置</option>
          <option value="legacy">基础语音链路</option>
          <option v-for="v in profiles.data.value?.profiles || []" :key="v.id" :value="v.id">
            {{ v.name }}
          </option>
        </select></label
      ></template
    ></el-card
  >
</template>
