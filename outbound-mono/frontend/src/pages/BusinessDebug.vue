<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { request } from '../session';
import { useData } from '../data';
import DebugVoice from '../components/DebugVoice.vue';
const debugMode = ref('text');
const route = useRoute(),
  router = useRouter(),
  scripts = useData('/api/v1/workspace/scripts?size=200');
const history = ref([]),
  active = ref(null),
  selected = ref(route.query.script_id ? Number(route.query.script_id) : null),
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
const actionLabel = (value) =>
  ({
    speak: '继续沟通',
    transfer: '转人工',
    handoff: '转人工',
    hangup: '结束沟通',
    end: '结束沟通',
    qualify: '记录意向',
    wait: '等待回复',
    clarify: '询问澄清',
  })[value] || '流程处理';
const intentLabel = (value) =>
  ({
    question: '客户提问',
    affirm: '肯定',
    deny: '否定',
    busy: '稍后联系',
    reject: '拒绝',
    optout: '拒绝后续联系',
    handoff: '请求人工',
    human: '请求人工',
    unknown: '未识别',
    silence: '未回应',
    greet: '问候',
    interested: '有意向',
  })[value] || '其他意图';
const fieldLabel = (key) =>
  ({ name: '客户姓名', phone: '手机号码' })[key] ||
  [
    ...(active.value?.snapshot.import_fields || []),
    ...(active.value?.snapshot.policy?.slots || []),
  ].find((f) => f.key === key)?.label ||
  key;
function displayValue(value) {
  if (value == null || value === '') return '未填写';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (Array.isArray(value)) return value.join('、');
  if (typeof value === 'object') return displayValue(value.value);
  return String(value);
}
function nodeLabel(value) {
  if (value === 'opening') return '开场';
  if (String(value || '').startsWith('collect:')) return '采集：' + fieldLabel(value.slice(8));
  const node = active.value?.snapshot.flow?.graph?.nodes?.find(
    (n) => String(n.id) === String(value),
  );
  return node?.data?.label || node?.label || node?.name || '流程节点';
}
const traceLabel = (value) =>
  String(value).replace(
    /(使用发布版本的未命中处理：)\s*(clarify|handoff|end)/,
    (_, prefix, strategy) => prefix + actionLabel(strategy),
  );
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
async function reset() {
  if (busy.value) return;
  active.value = null;
  variables.value = {};
  text.value = '';
  turnKey = '';
  error.value = '';
  await router.replace({ query: selected.value ? { script_id: selected.value } : {} });
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
  <el-radio-group v-model="debugMode"
    ><el-radio-button value="text">文字调试</el-radio-button
    ><el-radio-button value="voice">语音调试</el-radio-button></el-radio-group
  ><DebugVoice
    v-if="debugMode === 'voice'"
    :script-id="selected"
    :opening="active?.snapshot.opening || publication?.opening"
    :reply="latest?.reply"
    @transcript="
      (v) => {
        text = v;
        turnKey = '';
      }
    "
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
      ><el-button @click="reset">重置调试</el-button
      ><template v-if="active"
        ><p>
          会话 {{ active.name }} · v{{ active.script_version }} ·
          {{ active.state === 'active' ? '进行中' : '已结束' }}
        </p>
        <dl v-if="Object.keys(active.snapshot.variables || {}).length" class="debug-values">
          <template v-for="(value, key) in active.snapshot.variables" :key="key"
            ><dt>{{ fieldLabel(key) }}</dt>
            <dd>{{ displayValue(value) }}</dd></template
          >
        </dl>
        <p v-else>未填写客户变量</p>
        <details>
          <summary>冻结版本摘要</summary>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="话术版本"
              >v{{ active.script_version }}</el-descriptions-item
            >
            <el-descriptions-item label="业务目标">{{
              active.snapshot.goal || active.snapshot.policy?.goal || '未设置'
            }}</el-descriptions-item>
            <el-descriptions-item label="开场白">{{
              active.snapshot.policy?.opening || '未设置'
            }}</el-descriptions-item>
            <el-descriptions-item label="知识使用方式"
              >使用创建会话时的发布版本，本轮命中在右侧显示</el-descriptions-item
            >
          </el-descriptions>
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
          <small>第 {{ turn.turn_number }} 轮 · {{ actionLabel(turn.result.action) }}</small>
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
        ><p>
          节点：{{ nodeLabel(latest.node) }} · 意图：{{ intentLabel(latest.intent) }} · 动作：{{
            actionLabel(latest.action)
          }}
        </p>
        <p>客户变量 / 已采集字段</p>
        <dl v-if="Object.keys(latest.variables || {}).length" class="debug-values">
          <template v-for="(value, key) in latest.variables" :key="key"
            ><dt>{{ fieldLabel(key) }}</dt>
            <dd>{{ displayValue(value) }}</dd></template
          >
        </dl>
        <p v-else>尚无采集结果</p>
        <p v-if="latest.pending_slot?.key">
          等待确认：{{ fieldLabel(latest.pending_slot.key) }} ·
          {{ displayValue(latest.pending_slot.value) }}
        </p>
        <p v-for="(value, key) in latest.collected || {}" :key="key">
          {{ fieldLabel(key) }}：{{ displayValue(value) }} ·
          {{ value.waived ? '已跳过' : value.confirmed ? '已确认' : '待确认' }}
        </p>
        <p>知识命中</p>
        <div v-for="hit in latest.knowledge" :key="hit.id">
          <strong>{{ hit.title }} · v{{ hit.version }}</strong>
          <p>{{ hit.content }}</p>
          <small>{{ hit.source }}</small>
        </div>
        <el-empty v-if="!latest.knowledge.length" description="本轮无知识命中" />
        <p v-for="trace in latest.trace" :key="trace">{{ traceLabel(trace) }}</p>
        <details>
          <summary>节点定位信息</summary>
          <p>{{ latest.node }}</p>
        </details>
        <el-button @click="router.push('/admin/knowledge?tab=gaps')"
          >查看待补知识</el-button
        ></template
      >
      <p>历史会话（点击恢复）</p>
      <el-button v-for="s in history" :key="s.id" link @click="run(() => open(s.id))"
        >{{ s.name }} · v{{ s.script_version }} ·
        {{ s.state === 'active' ? '进行中' : '已结束' }}</el-button
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
.debug-values {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 8px;
}
.debug-values dt {
  color: #64748b;
}
.debug-values dd {
  margin: 0;
  overflow-wrap: anywhere;
}
@media (max-width: 1100px) {
  .debug-grid {
    grid-template-columns: 1fr;
  }
}
</style>
