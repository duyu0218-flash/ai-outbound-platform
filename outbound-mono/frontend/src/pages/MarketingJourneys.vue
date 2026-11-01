<script setup>
import { ref, computed } from 'vue';
import { request } from '../session';
import { useData, action } from '../data';
import { formatDate } from '../api';
import { ElMessage } from 'element-plus';

const query = useData('/api/v1/marketing/journeys');
const vocabulary = useData('/api/v1/marketing/ping');
const statusText = {
  draft: '草稿',
  published: '已发布',
  pending_approval: '待审批',
  running: '运行中',
  paused: '已暂停',
  stopped: '已停止',
  completed: '已完成',
};
const statusType = {
  running: 'success',
  pending_approval: 'warning',
  paused: 'warning',
  stopped: 'info',
  completed: 'info',
};
const typeText = {
  sms: '发送短信',
  call: 'AI 外呼',
  wait: '等待',
  condition: '条件分支',
  end: '结束',
};
const codeText = {
  LINK_CLICKED: '点击短链',
  LINK_CONVERTED: '落地页转化',
  SMS_DELIVERED: '短信送达',
  CALL_ANSWERED: '外呼接通',
  ANS_CONVERSATION: '有效对话',
  INTENT_A: '意向 A',
  INTENT_B: '意向 B',
  INTENT_C: '意向 C',
  CONVERTED: '已转化',
};
const actionText = {
  sent: '已发送',
  denied: '被拦截',
  control: '对照组记录',
  waited: '等待完成',
  yes: '满足',
  no: '不满足/超时',
  end: '结束',
  failed: '失败',
};
const stateText = {
  ready: '待执行',
  waiting: '等待结果',
  running: '执行中',
  done: '已完成',
  stopped: '已停止',
};
const codes = computed(() =>
  (vocabulary.data.value?.codes || []).map((c) => ({
    value: c,
    label: codeText[c] ? `${codeText[c]}（${c}）` : c,
  })),
);
const caps = computed(() => query.data.value?.capabilities || {});
const connected = computed(() => caps.value.snapshot_members && caps.value.channel_sender);

// ---------- editor ----------
const editing = ref(false),
  busy = ref(false),
  current = ref(null),
  form = ref({ name: '', description: '' }),
  nodes = ref([]),
  startNode = ref(''),
  snapshotId = ref(''),
  estimate = ref(null);
let serial = 0;
const newId = (type) => {
  let id;
  do id = `${type}${++serial}`;
  while (nodes.value.some((n) => n.id === id));
  return id;
};
function blank(type) {
  const n = { id: newId(type), type, name: '' };
  if (type === 'sms')
    n.sms = { applicationId: '', templateId: '', landingUrl: '', senderId: '', productId: '' };
  if (type === 'call') n.campaignId = null;
  if (type === 'wait') n.hours = 24;
  if (type === 'condition') {
    n.codes = ['LINK_CLICKED'];
    n.windowHours = 0;
  }
  return n;
}
function template() {
  serial = 0;
  const s1 = blank('sms'),
    w = blank('wait'),
    c = blank('condition'),
    call = blank('call'),
    s2 = blank('sms'),
    end = blank('end');
  s1.name = '首条短信（含短链）';
  s1.next = w.id;
  w.next = c.id;
  c.name = '是否点击短链';
  c.yes = call.id;
  c.no = s2.id;
  call.name = '点击用户 AI 外呼';
  call.next = end.id;
  s2.name = '未点击补发短信';
  s2.next = end.id;
  return [s1, w, c, call, s2, end];
}
function open(row) {
  estimate.value = null;
  snapshotId.value = row?.snapshot_id || '';
  if (!row) {
    current.value = null;
    form.value = { name: '', description: '' };
    nodes.value = template();
    startNode.value = nodes.value[0].id;
    editing.value = true;
    return;
  }
  load(row.id).then(() => (editing.value = true));
}
async function load(id) {
  const j = await action(() => request('/api/v1/marketing/journeys/' + id), { success: '' });
  current.value = j;
  form.value = { name: j.name, description: j.description || '' };
  nodes.value = JSON.parse(JSON.stringify(j.draft.nodes || []));
  startNode.value = j.draft.start || nodes.value[0]?.id;
  serial = nodes.value.length;
}
const options = (self) =>
  nodes.value
    .filter((n) => n.id !== self.id)
    .map((n) => ({ value: n.id, label: `${n.id} · ${n.name || typeText[n.type]}` }));
function addNode(type) {
  const n = blank(type);
  const last = nodes.value.at(-1);
  if (last && !last.next && last.type !== 'end' && last.type !== 'condition') last.next = n.id;
  nodes.value.push(n);
}
function removeNode(i) {
  const [gone] = nodes.value.splice(i, 1);
  for (const n of nodes.value)
    for (const k of ['next', 'yes', 'no']) if (n[k] === gone.id) n[k] = undefined;
  if (startNode.value === gone.id) startNode.value = nodes.value[0]?.id;
}
function move(i, d) {
  const j = i + d;
  if (j < 0 || j >= nodes.value.length) return;
  const list = nodes.value;
  [list[i], list[j]] = [list[j], list[i]];
}
function definition() {
  return { start: startNode.value, nodes: nodes.value };
}
// Branch preview: walk from the start node, indenting under conditions.
const preview = computed(() => {
  const byId = Object.fromEntries(nodes.value.map((n) => [n.id, n])),
    lines = [];
  const walk = (id, depth, label, seen) => {
    const n = byId[id];
    const pad = '　'.repeat(depth * 2);
    if (!n) {
      lines.push(`${pad}${label}⚠ 未设置下一步`);
      return;
    }
    if (seen.has(id)) {
      lines.push(`${pad}${label}↺ ${n.id}（形成循环，无法发布）`);
      return;
    }
    const s = new Set(seen).add(id);
    let text = `${n.name || typeText[n.type]}`;
    if (n.type === 'wait') text += `（${n.hours} 小时）`;
    if (n.type === 'condition')
      text = `判断：${(n.codes || []).map((c) => codeText[c] || c).join(' 或 ')}${n.windowHours ? `，最多等 ${n.windowHours} 小时` : ''}`;
    lines.push(`${pad}${label}${typeText[n.type]} · ${text}`);
    if (n.type === 'end') return;
    if (n.type === 'condition') {
      walk(n.yes, depth + 1, '✔ 满足 → ', s);
      walk(n.no, depth + 1, '✘ 不满足/超时 → ', s);
    } else walk(n.next, depth, '↓ ', s);
  };
  if (startNode.value) walk(startNode.value, 0, '▶ ', new Set());
  return lines;
});
async function save() {
  busy.value = true;
  try {
    const body = { ...form.value, definition: definition() };
    const saved = await action(
      () =>
        current.value
          ? request('/api/v1/marketing/journeys/' + current.value.id, 'PUT', body)
          : request('/api/v1/marketing/journeys', 'POST', body),
      { refresh: query.load, success: '草稿已保存' },
    );
    current.value = saved;
    return saved;
  } finally {
    busy.value = false;
  }
}
async function publish() {
  const saved = await save();
  const v = await action(
    () => request(`/api/v1/marketing/journeys/${saved.id}/publish`, 'POST', {}),
    {
      refresh: query.load,
      success: '',
    },
  );
  ElMessage.success(
    v.created ? `已发布为版本 v${v.version}` : `内容未变化，仍为版本 v${v.version}`,
  );
  await load(saved.id);
}
async function runEstimate() {
  estimate.value = await action(
    () =>
      request(`/api/v1/marketing/journeys/${current.value.id}/estimate`, 'POST', {
        snapshotId: snapshotId.value,
      }),
    { success: '' },
  );
}
async function lifecycle(verb, confirm) {
  await action(
    () =>
      request(
        `/api/v1/marketing/journeys/${current.value.id}/${verb}`,
        'POST',
        verb === 'start' ? { snapshotId: snapshotId.value } : {},
      ),
    { confirm, refresh: query.load },
  );
  await load(current.value.id);
}
const money = (v) => (v === null || v === undefined ? '-' : `¥${Number(v).toFixed(2)}`);

// ---------- run state ----------
const runtimeOpen = ref(false),
  runtime = ref(null);
async function showRuntime(row) {
  runtime.value = await action(() => request(`/api/v1/marketing/journeys/${row.id}/runtime`), {
    success: '',
  });
  runtimeOpen.value = true;
}
const summary = (obj, names) =>
  Object.entries(obj || {})
    .map(([k, v]) => `${names[k] || k} ${v}`)
    .join('，') || '-';

// ---------- policy ----------
const policyOpen = ref(false),
  policy = ref({}),
  rulesText = ref('');
const policyFields = [
  { key: 'window_start', label: '可联系开始时间', hint: 'HH:mm，例如 09:00' },
  { key: 'window_end', label: '可联系结束时间', hint: 'HH:mm，例如 20:00；全天填 24:00' },
  { key: 'timezone', label: '时区', hint: '例如 Asia/Shanghai' },
  { key: 'sms_daily_cap', label: '每人每天短信上限', type: 'number' },
  { key: 'sms_weekly_cap', label: '每人 7 天短信上限', type: 'number' },
  { key: 'call_daily_cap', label: '每人每天外呼上限', type: 'number' },
  { key: 'call_weekly_cap', label: '每人 7 天外呼上限', type: 'number' },
  {
    key: 'sms_call_gap_minutes',
    label: '短信后多少分钟内不外呼',
    type: 'number',
    hint: '用户点击短链后不受此限制',
  },
  { key: 'sms_unit_price', label: '短信单价（元）' },
  { key: 'call_unit_price', label: '外呼单价（元/通）' },
  {
    key: 'approval_threshold',
    label: '启动审批阈值（元）',
    hint: '预估成本超过此值需另一位管理员审批',
  },
];
async function openPolicy() {
  policy.value = await action(() => request('/api/v1/marketing/policy'), { success: '' });
  rulesText.value = JSON.stringify(policy.value.stage_rules, null, 2);
  policyOpen.value = true;
}
async function savePolicy() {
  let rules;
  try {
    rules = JSON.parse(rulesText.value);
  } catch {
    ElMessage.error('阶段规则不是有效的 JSON');
    return;
  }
  const { customized, stage_rules, ...rest } = policy.value;
  await action(
    () => request('/api/v1/marketing/policy', 'PUT', { ...rest, stage_rules: rules }),
    {},
  );
  policyOpen.value = false;
}
</script>
<template>
  <PageHeader
    title="营销活动编排"
    description="配置“短信 → 点击 → 外呼”等组合策略，设置跨渠道频控并发布活动。"
    ><el-button @click="openPolicy">频控与单价设置</el-button
    ><el-button type="primary" @click="open(null)">新建活动</el-button></PageHeader
  >
  <el-alert
    v-if="query.data.value && !connected"
    type="warning"
    :closable="false"
    show-icon
    title="数据模块未接入：人群快照与发送通道（P1）待接入。可以编辑活动草稿，含短信/外呼节点的活动暂不能发布或启动。"
    style="margin-bottom: 12px"
  />
  <el-card>
    <el-alert v-if="query.error.value" :title="query.error.value" type="error" />
    <DataGrid
      :rows="query.data.value?.items || []"
      :loading="query.loading.value"
      :pagination="20"
      :columns="[
        { key: 'name', label: '活动名称' },
        { key: 'status', label: '状态', render: (v) => statusText[v] || v },
        { key: 'latest_version', label: '最新版本', render: (v) => (v ? 'v' + v : '未发布') },
        { key: 'run_version', label: '运行版本', render: (v) => (v ? 'v' + v : '-') },
        { key: 'members', label: '成员数' },
        { key: 'active_members', label: '进行中' },
        { key: 'created_at', label: '创建时间', date: true },
      ]"
      ><template #actions="{ row }"
        ><el-button size="small" type="primary" @click="open(row)">编辑 / 启动</el-button
        ><el-button size="small" @click="showRuntime(row)">运行情况</el-button></template
      ></DataGrid
    >
  </el-card>

  <el-drawer
    v-model="editing"
    size="82%"
    :title="current ? `活动：${current.name}` : '新建活动'"
    destroy-on-close
  >
    <div v-if="current" class="actions" style="margin-bottom: 12px">
      <el-tag :type="statusType[current.status]">{{ statusText[current.status] }}</el-tag>
      <span class="hint"
        >最新版本 {{ current.latest_version ? 'v' + current.latest_version : '未发布' }}
        <template v-if="current.run_version">，运行版本 v{{ current.run_version }}</template>
        。已发布版本不可修改，修改后再次发布会生成新版本，运行中的成员继续使用原版本。</span
      >
    </div>
    <el-form label-width="96px">
      <el-form-item label="活动名称"><el-input v-model="form.name" maxlength="128" /></el-form-item>
      <el-form-item label="说明"
        ><el-input v-model="form.description" type="textarea" :rows="2" maxlength="512"
      /></el-form-item>
      <el-form-item label="起始节点"
        ><el-select v-model="startNode" style="width: 260px"
          ><el-option
            v-for="n in nodes"
            :key="n.id"
            :value="n.id"
            :label="`${n.id} · ${n.name || typeText[n.type]}`" /></el-select
      ></el-form-item>
    </el-form>
    <el-row :gutter="16">
      <el-col :span="15">
        <el-card v-for="(n, i) in nodes" :key="n.id" shadow="never" style="margin-bottom: 10px">
          <template #header>
            <div class="actions" style="justify-content: space-between">
              <span
                ><el-tag size="small">{{ n.id }}</el-tag> {{ typeText[n.type] }}</span
              >
              <span
                ><el-button size="small" text @click="move(i, -1)">上移</el-button
                ><el-button size="small" text @click="move(i, 1)">下移</el-button
                ><el-button size="small" text type="danger" @click="removeNode(i)"
                  >删除</el-button
                ></span
              >
            </div>
          </template>
          <el-form label-width="110px" size="small">
            <el-form-item label="节点名称"
              ><el-input v-model="n.name" placeholder="便于运营识别，可不填"
            /></el-form-item>
            <template v-if="n.type === 'sms'">
              <el-form-item label="消息应用 ID" required
                ><el-input v-model="n.sms.applicationId"
              /></el-form-item>
              <el-form-item label="短信模板 ID" required
                ><el-input v-model="n.sms.templateId"
              /></el-form-item>
              <el-form-item label="落地页地址"
                ><el-input
                  v-model="n.sms.landingUrl"
                  placeholder="填写后模板中的 {link} 会替换为每人一条的短链"
              /></el-form-item>
              <el-form-item label="签名 / 产品"
                ><el-input v-model="n.sms.senderId" placeholder="签名 ID" style="width: 48%" />
                <el-input
                  v-model="n.sms.productId"
                  placeholder="产品 ID"
                  style="width: 48%; margin-left: 4%"
              /></el-form-item>
            </template>
            <el-form-item v-if="n.type === 'call'" label="外呼任务编号" required
              ><el-input-number v-model="n.campaignId" :min="1" controls-position="right"
            /></el-form-item>
            <el-form-item v-if="n.type === 'wait'" label="等待时长（小时）"
              ><el-input-number v-model="n.hours" :min="0.1" :max="1440" :step="1"
            /></el-form-item>
            <template v-if="n.type === 'condition'">
              <el-form-item label="出现以下结果"
                ><el-select v-model="n.codes" multiple filterable style="width: 100%"
                  ><el-option
                    v-for="c in codes"
                    :key="c.value"
                    :value="c.value"
                    :label="c.label" /></el-select
              ></el-form-item>
              <el-form-item label="最多等待（小时）"
                ><el-input-number v-model="n.windowHours" :min="0" :max="1440" />
                <span class="hint" style="margin-left: 8px"
                  >0 表示立即判断；超时未出现走“不满足”分支</span
                ></el-form-item
              >
              <el-form-item label="满足 →"
                ><el-select v-model="n.yes" style="width: 100%"
                  ><el-option v-for="o in options(n)" :key="o.value" v-bind="o" /></el-select
              ></el-form-item>
              <el-form-item label="不满足 / 超时 →"
                ><el-select v-model="n.no" style="width: 100%"
                  ><el-option v-for="o in options(n)" :key="o.value" v-bind="o" /></el-select
              ></el-form-item>
            </template>
            <el-form-item v-if="['sms', 'call', 'wait'].includes(n.type)" label="下一步 →"
              ><el-select v-model="n.next" style="width: 100%"
                ><el-option v-for="o in options(n)" :key="o.value" v-bind="o" /></el-select
            ></el-form-item>
          </el-form>
        </el-card>
        <div class="actions">
          <span class="hint">添加节点：</span>
          <el-button v-for="(label, t) in typeText" :key="t" size="small" @click="addNode(t)">{{
            label
          }}</el-button>
        </div>
      </el-col>
      <el-col :span="9">
        <el-card shadow="never" header="分支预览">
          <div v-for="(l, i) in preview" :key="i" style="line-height: 1.9; white-space: pre">
            {{ l }}
          </div>
        </el-card>
        <el-card shadow="never" header="保存与发布" style="margin-top: 12px">
          <div class="actions">
            <el-button :loading="busy" @click="save">保存草稿</el-button>
            <el-button type="primary" :loading="busy" @click="publish">保存并发布</el-button>
          </div>
        </el-card>
        <el-card v-if="current" shadow="never" header="启动与运行控制" style="margin-top: 12px">
          <template v-if="['published'].includes(current.status)">
            <el-input v-model="snapshotId" placeholder="人群快照编号（在“人群圈选”页复制）" />
            <div class="actions" style="margin-top: 8px">
              <el-button :disabled="!snapshotId" @click="runEstimate">预估触达与成本</el-button>
              <el-button
                type="primary"
                :disabled="!snapshotId"
                @click="lifecycle('start', '确认按最新版本启动活动？启动后将向快照成员发送。')"
                >启动活动</el-button
              >
            </div>
          </template>
          <el-descriptions
            v-if="estimate || current.estimate"
            :column="1"
            border
            size="small"
            style="margin-top: 10px"
          >
            <template v-for="e in [estimate || current.estimate]" :key="'e'">
              <el-descriptions-item label="快照人数">{{ e.total }}</el-descriptions-item>
              <el-descriptions-item label="实验组 / 对照组"
                >{{ e.test }} / {{ e.control }}</el-descriptions-item
              >
              <el-descriptions-item label="可触达">{{ e.reachable }}</el-descriptions-item>
              <el-descriptions-item label="准入过滤"
                >{{ e.filtered }}
                <span class="hint">{{ summary(e.filtered_reasons, {}) }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="预估成本"
                >{{ money(e.estimated_cost) }}（每人最多 {{ money(e.cost_per_member) }}）
              </el-descriptions-item>
              <el-descriptions-item label="审批"
                >{{ e.needs_approval ? '超过阈值，需要另一位管理员审批' : '无需审批' }}（阈值
                {{ money(e.approval_threshold) }}）</el-descriptions-item
              >
            </template>
          </el-descriptions>
          <div class="actions" style="margin-top: 10px">
            <template v-if="current.status === 'pending_approval'">
              <el-button type="primary" @click="lifecycle('approve', '确认批准并启动活动？')"
                >批准启动</el-button
              >
              <el-button @click="lifecycle('reject')">驳回</el-button>
              <span class="hint">提交人不能自己审批</span>
            </template>
            <el-button v-if="current.status === 'running'" @click="lifecycle('pause')"
              >暂停</el-button
            >
            <el-button
              v-if="current.status === 'paused'"
              type="primary"
              @click="lifecycle('resume')"
              >继续</el-button
            >
            <el-button
              v-if="['running', 'paused', 'pending_approval'].includes(current.status)"
              type="danger"
              @click="lifecycle('stop', '停止后不可恢复，未完成的成员将不再触达。确认停止？')"
              >停止</el-button
            >
          </div>
        </el-card>
      </el-col>
    </el-row>
  </el-drawer>

  <el-drawer
    v-model="runtimeOpen"
    size="70%"
    :title="runtime ? `运行情况：${runtime.journey.name}` : '运行情况'"
  >
    <template v-if="runtime">
      <p class="hint">
        状态 {{ statusText[runtime.journey.status] }}，运行版本
        {{ runtime.journey.run_version ? 'v' + runtime.journey.run_version : '-' }}，开始于
        {{ formatDate(runtime.journey.started_at) }}
      </p>
      <el-card shadow="never" header="各节点人数与执行结果">
        <el-table :data="runtime.nodes" stripe>
          <el-table-column label="节点" min-width="160"
            ><template #default="{ row }"
              >{{ row.id }} · {{ row.name || typeText[row.type] }}</template
            ></el-table-column
          >
          <el-table-column label="类型" width="100"
            ><template #default="{ row }">{{ typeText[row.type] }}</template></el-table-column
          >
          <el-table-column label="停留在此的成员" min-width="180"
            ><template #default="{ row }">{{
              summary(row.members, stateText)
            }}</template></el-table-column
          >
          <el-table-column label="执行结果" min-width="200"
            ><template #default="{ row }">{{
              summary(row.steps, actionText)
            }}</template></el-table-column
          >
        </el-table>
      </el-card>
      <el-row :gutter="12" style="margin-top: 12px">
        <el-col :span="12"
          ><el-card shadow="never" header="分组与状态"
            ><el-table :data="runtime.groups" size="small"
              ><el-table-column label="分组"
                ><template #default="{ row }">{{
                  row.grp === 'control' ? '对照组' : '实验组'
                }}</template></el-table-column
              ><el-table-column label="状态"
                ><template #default="{ row }">{{
                  stateText[row.state] || row.state
                }}</template></el-table-column
              ><el-table-column prop="n" label="人数" /></el-table></el-card
        ></el-col>
        <el-col :span="12"
          ><el-card shadow="never" header="触达结果"
            ><el-table :data="runtime.outcomes" size="small"
              ><el-table-column label="结果"
                ><template #default="{ row }">{{
                  codeText[row.code] ? `${codeText[row.code]}（${row.code}）` : row.code
                }}</template></el-table-column
              ><el-table-column prop="n" label="次数" /></el-table></el-card
        ></el-col>
      </el-row>
      <el-card shadow="never" header="最近执行记录" style="margin-top: 12px">
        <DataGrid
          :rows="runtime.recent"
          :pagination="10"
          :columns="[
            { key: 'created_at', label: '时间', date: true },
            { key: 'node_id', label: '节点' },
            { key: 'node_type', label: '类型', render: (v) => typeText[v] || v },
            { key: 'action', label: '结果', render: (v) => actionText[v] || v },
            { key: 'grp', label: '分组', render: (v) => (v === 'control' ? '对照组' : '实验组') },
            { key: 'reason', label: '说明' },
          ]"
        />
      </el-card>
    </template>
  </el-drawer>

  <el-dialog v-model="policyOpen" title="频控与单价设置" width="720px" destroy-on-close>
    <el-form label-width="200px" size="small">
      <el-form-item v-for="f in policyFields" :key="f.key" :label="f.label">
        <el-input-number v-if="f.type === 'number'" v-model="policy[f.key]" :min="0" />
        <el-input v-else v-model="policy[f.key]" />
        <div v-if="f.hint" class="hint">{{ f.hint }}</div>
      </el-form-item>
      <el-form-item label="用户阶段规则（JSON）">
        <el-input v-model="rulesText" type="textarea" :rows="8" />
        <div class="hint">
          按顺序匹配，第一条满足的规则决定阶段；条件可用 anyCodes / noneCodes / minTouches。
        </div>
      </el-form-item>
    </el-form>
    <template #footer
      ><el-button @click="policyOpen = false">取消</el-button
      ><el-button type="primary" @click="savePolicy">保存</el-button></template
    >
  </el-dialog>
</template>
