<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { request } from '../session';
import { useData } from '../data';
const route = useRoute(),
  router = useRouter(),
  scripts = useData('/api/v1/workspace/scripts?size=200');
const history = ref([]),
  active = ref(null),
  selected = ref(null),
  name = ref('业务调试'),
  variables = ref({}),
  text = ref(''),
  busy = ref(false),
  error = ref('');
const publication = computed(
  () => scripts.data.value?.rows?.find((s) => String(s.id) === String(selected.value))?.published,
);
const fields = computed(() => publication.value?.import_fields || []);
const latest = computed(() => active.value?.turns?.at(-1)?.result);
async function run(fn) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await fn();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function load() {
  history.value = await request('/api/v1/workspace/debug-sessions');
}
async function open(id) {
  active.value = await request('/api/v1/workspace/debug-sessions/' + id);
  selected.value = Number(active.value.script_id);
  name.value = active.value.name;
  variables.value = JSON.parse(JSON.stringify(active.value.snapshot.variables || {}));
  await router.replace({ query: { session_id: id } });
}
async function create() {
  await run(async () => {
    const v = await request('/api/v1/workspace/debug-sessions', 'POST', {
      request_key: crypto.randomUUID(),
      name: name.value,
      script_id: String(selected.value || ''),
      variables: variables.value,
    });
    await open(v.id);
    await load();
  });
}
let turnKey = '';
async function send() {
  if (!text.value.trim()) return;
  await run(async () => {
    turnKey ||= crypto.randomUUID();
    active.value = await request(
      '/api/v1/workspace/debug-sessions/' + active.value.id + '/turns',
      'POST',
      { request_key: turnKey, expected_revision: active.value.revision, text: text.value },
    );
    turnKey = '';
    text.value = '';
    await load();
  });
}
async function end() {
  await run(async () => {
    active.value = await request(
      '/api/v1/workspace/debug-sessions/' + active.value.id + '/end',
      'POST',
      { expected_revision: active.value.revision },
    );
    await load();
  });
}
onMounted(() =>
  run(async () => {
    await load();
    selected.value = route.query.script_id || null;
    if (route.query.session_id) await open(route.query.session_id);
  }),
);
</script>
<template>
  <PageHeader
    title="业务调试"
    description="冻结已发布话术、知识版本和客户变量，保存逐轮结果并支持恢复"
    ><el-button @click="router.push('/admin/scripts')">返回话术管理</el-button></PageHeader
  >
  <el-alert v-if="error" type="error" :title="error" />
  <el-alert
    title="当前文本调试使用实际本地意图、知识排序和流程画布规则；不调用模型，不拨号、不发送短信、不创建正式线索。语音试聊需单独确认费用和麦克风授权。"
    type="info"
    :closable="false"
  />
  <div class="debug-grid">
    <el-card header="配置与快照"
      ><el-form label-position="top"
        ><el-form-item label="调试名称"
          ><el-input v-model="name" aria-label="调试名称" /></el-form-item
        ><el-form-item label="已发布话术"
          ><el-select v-model="selected" aria-label="调试话术"
            ><el-option
              v-for="s in scripts.data.value?.rows?.filter(
                (s) => s.published && s.state !== 'disabled',
              ) || []"
              :key="s.id"
              :value="s.id"
              :label="s.name + ' · v' + s.published_version" /></el-select></el-form-item
        ><el-form-item label="客户姓名"
          ><el-input v-model="variables.name" aria-label="调试客户姓名" /></el-form-item
        ><el-form-item v-for="f in fields" :key="f.key" :label="f.label"
          ><el-input
            v-model="variables[f.key]"
            :aria-label="'调试变量' + f.key" /></el-form-item></el-form
      ><el-button type="primary" :loading="busy" @click="create">新建调试会话</el-button
      ><template v-if="active"
        ><p>
          会话 {{ active.name }} · v{{ active.script_version }} ·
          {{ active.state === 'active' ? '进行中' : '已结束' }}
        </p>
        <pre>{{ active.snapshot.variables }}</pre>
        <details>
          <summary>冻结话术与知识</summary>
          <pre>{{ active.snapshot }}</pre>
        </details>
        <el-button :disabled="active.state !== 'active'" @click="end">结束会话</el-button
        ><el-button
          @click="
            router.push({
              path: '/admin/bailian-trial',
              query: { script_id: active.script_id, debug_session_id: active.id },
            })
          "
          >进入百炼语音试聊</el-button
        ></template
      ></el-card
    >
    <el-card header="对话"
      ><el-empty v-if="!active" description="先选择已发布话术并建立会话" /><template v-else
        ><div v-for="turn in active.turns" :key="turn.id" class="debug-turn">
          <p>客户：{{ turn.input_text }}</p>
          <p>AI：{{ turn.result.reply || '（等待下一节点）' }}</p>
          <small>第 {{ turn.turn_number }} 轮 · {{ turn.result.action }}</small>
        </div>
        <el-input
          v-model="text"
          type="textarea"
          :rows="3"
          aria-label="业务调试输入"
          :disabled="active.state !== 'active'"
          @input="turnKey = ''"
        /><el-button
          type="primary"
          :loading="busy"
          :disabled="active.state !== 'active'"
          @click="send"
          >发送调试输入</el-button
        ><el-button @click="run(() => open(active.id))">刷新恢复</el-button></template
      ></el-card
    >
    <el-card header="逐轮观察"
      ><template v-if="latest"
        ><p>节点：{{ latest.node }} · 意图：{{ latest.intent }} · 动作：{{ latest.action }}</p>
        <p>客户变量 / 已采集字段</p>
        <pre>{{ latest.variables }}</pre>
        <p v-if="Object.keys(latest.pending_slot || {}).length">
          等待确认：{{ latest.pending_slot }}
        </p>
        <p v-if="Object.keys(latest.collected || {}).length">采集记录：{{ latest.collected }}</p>
        <p>知识命中</p>
        <div v-for="hit in latest.knowledge" :key="hit.id">
          <strong>{{ hit.title }} · v{{ hit.version }}</strong>
          <p>{{ hit.content }}</p>
          <small>{{ hit.source }}</small>
        </div>
        <el-empty v-if="!latest.knowledge.length" description="本轮无知识命中" />
        <p v-for="trace in latest.trace" :key="trace">{{ trace }}</p>
        <el-button @click="router.push('/admin/knowledge?tab=gaps')"
          >查看待补知识</el-button
        ></template
      >
      <p>历史会话（点击恢复）</p>
      <el-button v-for="s in history" :key="s.id" link @click="run(() => open(s.id))"
        >{{ s.name }} · v{{ s.script_version }} · {{ s.state }}</el-button
      ></el-card
    >
  </div>
</template>
<style scoped>
.debug-grid {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) minmax(320px, 1.5fr) minmax(260px, 1fr);
  gap: 16px;
  margin-top: 16px;
}
.debug-turn {
  padding: 12px;
  border-bottom: 1px solid #e6e8ef;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 12px;
  max-height: 360px;
  overflow: auto;
}
@media (max-width: 1100px) {
  .debug-grid {
    grid-template-columns: 1fr;
  }
}
</style>
