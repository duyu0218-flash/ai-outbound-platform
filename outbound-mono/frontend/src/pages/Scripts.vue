<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { request } from '../session';
import { formatDate } from '../api';
import { action, useData } from '../data';
import FlowDesigner from '../components/FlowDesigner.vue';
const clone = (v) => JSON.parse(JSON.stringify(v));
const route = useRoute(),
  page = ref(1),
  keyword = ref(''),
  query = useData(
    () =>
      `/api/v1/workspace/scripts?page=${page.value}&size=30&keyword=${encodeURIComponent(keyword.value)}`,
  ),
  libraries = useData('/api/v1/workspace/libraries'),
  teams = useData('/api/v1/ops/teams');
const skills = useData('/api/v1/workspace/catalog/skill');
const selected = ref(null),
  model = ref({}),
  tab = ref('dialogue'),
  busy = ref(false),
  error = ref(''),
  history = ref([]),
  flow = ref(null),
  probe = ref(null),
  sample = ref('我想了解办理条件'),
  createOpen = ref(false),
  newScript = ref({ name: '', category: '业务话术' });
const users = useData('/api/v1/admin/users?page=1&size=200');
const rows = computed(() => query.data.value?.rows || []);
const labels = { draft: '草稿', published: '已发布', review: '待审核', disabled: '已停用' };
const intents = {
  continue: '继续沟通',
  question: '客户提问',
  unclear: '需要澄清',
  stop_contact: '要求停止联系',
  wrong_person: '非本人或号码错误',
  cancel_callback: '取消回访',
  callback: '要求回访',
  decline_handoff: '不需要转人工',
  handoff: '要求转人工',
  end: '结束沟通',
  wait: '请稍候',
  machine_candidate: '疑似语音信箱',
  rejected: '明确拒绝',
  permission_to_listen: '愿意继续了解',
  interested: '表达意向',
  affirm: '肯定回应',
  deny: '否定回应',
};
const basic = computed(() => [
  { key: 'name', label: '话术名称', required: true },
  { key: 'category', label: '业务分类', required: true },
  { key: 'description', label: '适用场景', type: 'textarea', rows: 2 },
  {
    key: 'owner_id',
    label: '负责人',
    options: (users.data.value || []).map((v) => ({
      value: v.id,
      label: v.full_name || v.username,
    })),
  },
]);
const dialogue = [
  ['identity', 'AI身份'],
  ['goal', '业务目标'],
  ['opening', '开场白'],
  ['steps', '沟通步骤'],
  ['limits', '回答边界与禁止承诺'],
  ['ending', '结束语'],
  ['content', '补充话术'],
].map(([key, label]) => ({ key, label, type: 'textarea', rows: key === 'steps' ? 5 : 3 }));
const publicationReview = ref(null);
const rulesBySlot = new WeakMap();
function slotRule(slot) {
  model.value.slot_rules ||= [];
  let rule = rulesBySlot.get(slot);
  if (rule && model.value.slot_rules.includes(rule)) {
    if (rule.key !== slot.key) rule.key = slot.key;
    return rule;
  }
  rule = model.value.slot_rules.find((r) => r.key === slot.key);
  if (!rule) {
    model.value.slot_rules.push({
      key: slot.key,
      min_length: 1,
      max_length: 500,
      max_retries: 2,
      missing_action: 'end',
      retry_prompt: '',
    });
    rule = model.value.slot_rules.at(-1);
  }
  rulesBySlot.set(slot, rule);
  return rule;
}
const ruleFields = [
  { key: 'min_length', label: '最少字符', type: 'number', min: 0, max: 500 },
  { key: 'max_length', label: '最多字符', type: 'number', min: 1, max: 500 },
  { key: 'min_value', label: '数值下限（整数字段，可选）', type: 'number' },
  { key: 'max_value', label: '数值上限（整数字段，可选）', type: 'number' },
  { key: 'max_retries', label: '最多重问次数', type: 'number', min: 0, max: 3 },
  { key: 'retry_prompt', label: '校验失败重问提示（可选）' },
  {
    key: 'missing_action',
    label: '仍缺失时处理',
    options: [
      { value: 'end', label: '说明后结束' },
      { value: 'handoff', label: '转人工确认' },
      { value: 'skip', label: '记录缺失并继续' },
    ],
  },
];
const slotFields = [
  { key: 'key', label: '字段标识', required: true },
  { key: 'label', label: '字段名称', required: true },
  { key: 'kind', label: '字段类型', options: ['text', 'integer', 'choice', 'date', 'digits'] },
  { key: 'question', label: '询问话术', required: true },
  { key: 'required', label: '必填', type: 'boolean' },
  { key: 'confirm', label: '复述确认', type: 'boolean' },
  { key: 'choices', label: '可选值', options: [], multiple: true, tags: true },
  { key: 'qualifies', label: '合格取值', options: [], multiple: true, tags: true },
];
const behavior = computed(() => [
  {
    key: 'handoff_team_ids',
    label: '转接团队',
    multiple: true,
    options: (teams.data.value || []).map((v) => ({ value: v.id, label: v.name })),
  },
  {
    key: 'handoff_skills',
    label: '转接所需技能',
    multiple: true,
    options: [
      ...(skills.data.value || [])
        .filter((s) => s.enabled || (model.value.policy?.handoff_skills || []).includes(s.name))
        .map((s) => ({
          value: s.name,
          label:
            s.name +
            ' · ' +
            ({ business: '业务', region: '地区', language: '语言' }[s.draft.group] || ''),
        })),
      ...(model.value.policy?.handoff_skills || [])
        .filter((s) => !(skills.data.value || []).some((v) => v.name === s))
        .map((s) => ({ value: s, label: s + '（历史技能）' })),
    ],
  },
  { key: 'handoff_wait_seconds', label: '转接最长等待（秒）', type: 'number', min: 5, max: 300 },
  {
    key: 'handoff_fallback',
    label: '无人接待处理',
    options: [
      { value: 'end', label: '说明后结束' },
      { value: 'ai', label: '返回AI继续沟通' },
    ],
  },
  { key: 'handoff_auto_callback', label: '无人接待创建回拨待办', type: 'boolean' },
  { key: 'allow_interruptions', label: '允许打断', type: 'boolean' },
  { key: 'silence_seconds', label: '无应答等待（秒）', type: 'number', min: 3, max: 120 },
  { key: 'failure_prompt', label: '服务失败结束语' },
  { key: 'timezone', label: '服务时区' },
  { key: 'start_hour', label: '开始时刻', type: 'number', min: 0, max: 23 },
  { key: 'end_hour', label: '结束时刻', type: 'number', min: 1, max: 24 },
]);
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
async function open(row) {
  await run(async () => {
    selected.value = await request('/api/v1/workspace/scripts/' + row.id);
    model.value = clone(selected.value.draft);
    tab.value = 'dialogue';
    probe.value = null;
    history.value = selected.value.revision
      ? await request(`/api/v1/workspace/scripts/${row.id}/history`)
      : [];
  });
}
async function create() {
  await run(async () => {
    const v = await request('/api/v1/workspace/scripts', 'POST', newScript.value);
    createOpen.value = false;
    await query.load();
    selected.value = v;
    model.value = clone(v.draft);
    tab.value = 'dialogue';
    history.value = [];
    probe.value = null;
  });
}
async function copy(row) {
  await run(async () => {
    const value = await request('/api/v1/workspace/scripts/' + row.id + '/copy', 'POST');
    await query.load();
    selected.value = value;
    model.value = clone(value.draft);
    tab.value = 'dialogue';
    history.value = [];
    probe.value = null;
  });
}
async function save() {
  model.value.slot_rules = (model.value.slot_rules || []).filter((r) =>
    model.value.policy.slots.some((s) => s.key === r.key),
  );
  const v = await request(`/api/v1/workspace/scripts/${selected.value.id}/draft`, 'PUT', {
    draft: model.value,
    expected_revision: selected.value.revision,
  });
  selected.value = v;
  model.value = clone(v.draft);
  await query.load();
}
async function publish() {
  await run(async () => {
    await save();
    publicationReview.value = await request(
      `/api/v1/workspace/scripts/${selected.value.id}/publish-check`,
    );
  });
}
async function confirmPublish() {
  await run(async () => {
    selected.value = await request(
      `/api/v1/workspace/scripts/${selected.value.id}/publish`,
      'POST',
      { expected_revision: publicationReview.value.revision },
    );
    publicationReview.value = null;
    history.value = await request(`/api/v1/workspace/scripts/${selected.value.id}/history`);
    await query.load();
  });
}
async function state(value) {
  await run(async () => {
    await save();
    selected.value = await request(`/api/v1/workspace/scripts/${selected.value.id}/state`, 'POST', {
      state: value,
      expected_revision: selected.value.revision,
    });
    await query.load();
  });
}
async function restore(version) {
  await run(async () => {
    selected.value = await request(
      `/api/v1/workspace/scripts/${selected.value.id}/restore`,
      'POST',
      { version, expected_revision: selected.value.revision },
    );
    model.value = clone(selected.value.draft);
    await query.load();
  });
}
function addSlot() {
  model.value.policy.slots.push({
    key: '',
    label: '',
    kind: 'text',
    question: '',
    required: true,
    confirm: true,
    choices: [],
    qualifies: [],
  });
}
onMounted(async () => {
  if (route.query.script_id) await open({ id: route.query.script_id });
});
</script>
<template>
  <PageHeader title="话术管理" description="编排对话、采集业务信息并发布版本，任务引用已发布内容"
    ><el-button
      type="primary"
      @click="
        newScript = { name: '', category: '业务话术' };
        createOpen = true;
      "
      >新建话术</el-button
    ></PageHeader
  >
  <el-alert
    v-if="error || query.error.value"
    type="error"
    :title="error || query.error.value"
    :closable="false"
  />
  <template v-if="!selected"
    ><el-card
      ><el-input
        v-model="keyword"
        aria-label="搜索话术"
        placeholder="按名称或分类搜索"
        clearable
        style="max-width: 360px"
        @input="page = 1" />
      <el-table :data="rows" v-loading="query.loading.value"
        ><el-table-column label="话术名称"
          ><template #default="{ row }">{{ row.draft.name }}</template></el-table-column
        ><el-table-column label="分类"
          ><template #default="{ row }">{{ row.draft.category }}</template></el-table-column
        ><el-table-column label="状态"
          ><template #default="{ row }"
            ><el-tag>{{ labels[row.state] }}</el-tag
            ><small v-if="row.published_version && row.state === 'draft'"
              >已发布版本仍可被引用</small
            ></template
          ></el-table-column
        ><el-table-column prop="owner_name" label="负责人" /><el-table-column label="更新时间"
          ><template #default="{ row }">{{ formatDate(row.updated_at) }}</template></el-table-column
        ><el-table-column prop="published_version" label="发布版本" /><el-table-column
          prop="references"
          label="引用任务"
        /><el-table-column label="操作"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="open(row)">配置话术</el-button
            ><el-button link @click="copy(row)">复制</el-button></template
          ></el-table-column
        ></el-table
      >
      <Pager :page="page" :count="rows.length" @change="page = $event" /></el-card
  ></template>
  <template v-else>
    <div class="actions">
      <el-button
        :disabled="busy"
        @click="
          selected = null;
          query.load();
        "
        >返回话术列表</el-button
      ><strong>{{ model.name }}</strong
      ><el-tag>{{ labels[selected.state] }}</el-tag
      ><span
        >发布 v{{ selected.published_version }} · 引用 {{ selected.references || 0 }} 个任务</span
      ><el-button :loading="busy" @click="run(save)">保存草稿</el-button
      ><el-button :disabled="busy" @click="state('review')">提交审核</el-button
      ><el-button type="primary" :loading="busy" @click="publish">发布版本</el-button
      ><el-button type="danger" plain :disabled="busy" @click="state('disabled')"
        >停用话术</el-button
      >
    </div>
    <el-tabs v-model="tab"
      ><el-tab-pane name="dialogue" label="基本信息与对话" /><el-tab-pane
        name="flow"
        label="流程画布" /><el-tab-pane name="slots" label="信息采集与合格条件" /><el-tab-pane
        name="knowledge"
        label="知识与动作" /><el-tab-pane name="debug" label="调试" /><el-tab-pane
        name="history"
        label="版本记录"
    /></el-tabs>
    <el-card v-if="tab === 'dialogue'"
      ><FieldForm :disabled="busy" :model="model" :fields="basic" hide-submit /><FieldForm
        :disabled="busy"
        :model="model"
        :fields="dialogue"
        hide-submit
    /></el-card>
    <el-card v-if="tab === 'flow'"
      ><p>画布独立保存与发布。创建任务时选择具体画布版本，文字话术作为业务说明。</p>
      <el-button @click="flow = { id: selected.id, name: model.name }"
        >打开话术画布</el-button
      ></el-card
    >
    <el-card v-if="tab === 'slots'"
      ><p>
        字段用于对话采集，合格取值用于业务结果判定。字段标识供模板变量引用，例如 {{ '{姓名}' }}。
      </p>
      <el-card v-for="(slot, index) in model.policy.slots" :key="index" style="margin-bottom: 16px"
        ><FieldForm :disabled="busy" :model="slot" :fields="slotFields" hide-submit /><FieldForm
          :disabled="busy"
          v-if="slot.key"
          :model="slotRule(slot)"
          :fields="ruleFields"
          hide-submit
        /><el-button
          type="danger"
          link
          @click="
            model.slot_rules = (model.slot_rules || []).filter((r) => r.key !== slot.key);
            model.policy.slots.splice(index, 1);
          "
          >移除字段</el-button
        ></el-card
      ><el-button @click="addSlot">添加采集字段</el-button>
      <h3>名单导入变量</h3>
      <p>
        与对话采集分开：仅勾选导入必填的字段要求上传名单时提供；话术中使用的变量自动设为导入必填。
      </p>
      <el-card v-for="(field, index) in model.import_fields || []" :key="index"
        ><FieldForm
          :disabled="busy"
          :model="field"
          :fields="
            slotFields
              .filter((f) => !['question', 'confirm', 'qualifies'].includes(f.key))
              .map((f) => (f.key === 'required' ? { ...f, label: '导入必填' } : f))
          "
          hide-submit
        /><el-button link @click="model.import_fields.splice(index, 1)"
          >移除导入变量</el-button
        ></el-card
      ><el-button
        @click="
          model.import_fields ||= [];
          model.import_fields.push({
            key: '',
            label: '',
            kind: 'text',
            required: false,
            choices: [],
          });
        "
        >添加导入变量</el-button
      ></el-card
    >
    <el-card v-if="tab === 'knowledge'"
      ><FieldForm
        :disabled="busy"
        :model="model"
        :fields="[
          {
            key: 'unknown_strategy',
            label: '知识未命中处理',
            options: [
              { value: 'clarify', label: '记录问题并澄清' },
              { value: 'handoff', label: '转人工确认' },
              { value: 'end', label: '说明后结束' },
            ],
          },
          { key: 'unknown_prompt', label: '未命中提示', type: 'textarea' },
        ]"
        hide-submit /><el-alert
        title="动作由业务规则控制：拒绝/禁呼优先结束；回拨需要客户确认；转人工受团队技能及接待容量约束；挂机短信在任务详情单独选择已审核模板和预算。"
        :closable="false" /><el-form label-position="top"
        ><el-form-item label="关联知识库"
          ><el-select v-model="model.knowledge_library_ids" multiple aria-label="关联知识库"
            ><el-option
              v-for="lib in libraries.data.value?.rows || []"
              :key="lib.object_id"
              :label="`${lib.name} · 发布v${lib.published_version}`"
              :value="lib.object_id"
              :disabled="
                !lib.published_version || lib.state === 'disabled'
              " /></el-select></el-form-item
      ></el-form>
      <p>发布话术时冻结选中知识库的已发布条目；知识库后续修改不会替换已创建任务的知识快照。</p>
      <FieldForm :disabled="busy" :model="model.policy" :fields="behavior" hide-submit
    /></el-card>
    <el-card v-if="tab === 'debug'"
      ><el-alert
        title="预检当前编辑内容，不保存草稿、不改变发布状态。仅使用本地规则与已发布知识，不调用模型或拨号。"
        :closable="false"
      /><el-input v-model="sample" aria-label="调试输入" style="margin: 16px 0" /><el-button
        :loading="busy"
        @click="
          run(async () => {
            probe = await request(`/api/v1/workspace/scripts/${selected.id}/probe`, 'POST', {
              query: sample,
              draft: model,
            });
          })
        "
        >本地预检</el-button
      ><el-button
        @click="$router.push({ path: '/admin/business-debug', query: { script_id: selected.id } })"
        >进入业务调试工作台</el-button
      >
      <section v-if="probe" aria-label="话术预检结果" class="probe-result">
        <h3>规则识别</h3>
        <el-tag>{{ intents[probe.intent] || '需要人工确认' }}</el-tag>
        <p class="muted">这是本地规则识别结果，实际回复和执行动作需另行验证。</p>
        <h3>命中知识</h3>
        <el-card v-for="hit in probe.knowledge" :key="hit.id" shadow="never" class="knowledge-hit">
          <strong>{{ hit.title }}</strong>
          <p>{{ hit.content }}</p>
          <small>知识版本 v{{ hit.version || '—' }} · 来源：{{ hit.source || '未填写' }}</small>
        </el-card>
        <el-empty
          v-if="!probe.knowledge?.length"
          description="未命中已发布知识，请检查业务范围、问法和有效期；实际沟通应澄清或转人工。"
        />
        <h3>待采集字段</h3>
        <p class="muted">以下为配置中的采集要求，本地预检不会提取或保存客户信息。</p>
        <el-table v-if="probe.slots?.length" :data="probe.slots">
          <el-table-column prop="label" label="字段名称" />
          <el-table-column prop="question" label="询问话术" />
          <el-table-column label="必采"
            ><template #default="{ row }">{{
              row.required ? '是' : '否'
            }}</template></el-table-column
          >
        </el-table>
        <p v-else>未配置采集字段</p>
      </section>
    </el-card>
    <el-card v-if="tab === 'history'"
      ><el-table :data="history"
        ><el-table-column prop="version" label="发布版本" /><el-table-column
          prop="published_at"
          label="发布时间"
        /><el-table-column label="操作"
          ><template #default="{ row }"
            ><el-button link @click="restore(row.version)">恢复为草稿</el-button></template
          ></el-table-column
        ></el-table
      >
      <p>恢复只创建当前草稿，重新发布后才可供新任务使用，已保存任务不受影响。</p></el-card
    >
  </template>
  <el-dialog
    :model-value="!!publicationReview"
    title="发布前核对"
    width="min(1000px,95vw)"
    @close="publicationReview = null"
    ><template v-if="publicationReview"
      ><el-alert v-for="e in publicationReview.errors" :key="e" :title="e" type="error" /><el-alert
        v-for="w in publicationReview.warnings"
        :key="w"
        :title="w"
        type="warning" />
      <p>{{ publicationReview.version_note }}</p>
      <h3>变更对比</h3>
      <el-table :data="publicationReview.changes"
        ><el-table-column prop="field" label="配置项" /><el-table-column label="上次发布"
          ><template #default="{ row }">
            <pre>{{ row.before }}</pre>
          </template></el-table-column
        ><el-table-column label="本次草稿"
          ><template #default="{ row }">
            <pre>{{ row.after }}</pre>
          </template></el-table-column
        ></el-table
      >
      <h3>引用任务（{{ publicationReview.references.length }}）</h3>
      <el-table :data="publicationReview.references"
        ><el-table-column prop="name" label="任务" /><el-table-column
          prop="status"
          label="状态" /></el-table></template
    ><template #footer
      ><el-button @click="publicationReview = null">返回修改</el-button
      ><el-button
        type="primary"
        :disabled="!publicationReview?.ready"
        :loading="busy"
        @click="confirmPublish"
        >确认发布版本</el-button
      ></template
    ></el-dialog
  >
  <el-dialog v-model="createOpen" destroy-on-close title="新建话术" width="560px"
    ><FieldForm
      :disabled="busy"
      :model="newScript"
      :fields="[...basic.slice(0, 2), { key: 'goal', label: '本次话术目标', type: 'textarea' }]"
      :busy="busy"
      @submit="create" /></el-dialog
  ><FlowDesigner v-if="flow" :template="flow" @close="flow = null" />
</template>
<style scoped>
.probe-result {
  margin-top: 24px;
}
.knowledge-hit {
  margin: 12px 0;
}
.knowledge-hit p {
  white-space: pre-wrap;
}
.muted,
.knowledge-hit small {
  color: #64748b;
}
</style>
