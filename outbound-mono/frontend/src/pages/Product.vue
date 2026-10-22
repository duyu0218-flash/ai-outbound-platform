<script setup>
import { ref, reactive, computed, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { request } from '../session';
import { useData, action } from '../data';
const campaign = ref(),
  pages = reactive({ appointments: 1, work: 1, outcomes: 1 }),
  model = ref({ slots: [], retry_seconds: {} }),
  faq = ref('{}'),
  sample = ref('不用转人工'),
  probe = ref(null),
  busy = ref(false),
  times = reactive({}),
  knowledge = reactive({ title: '', content: '', source: '' }),
  search = ref(''),
  hits = ref([]),
  forms = ref([]);
const routingTeams = useData('/api/v1/ops/teams');
const api = (path, method = 'GET', body) => request('/api/v1/product' + path, method, body);
const policy = useData(
    () => '/api/v1/product/policy' + (campaign.value ? '?campaign_id=' + campaign.value : ''),
  ),
  appointments = useData(() => '/api/v1/product/appointments?page=' + pages.appointments),
  work = useData(() => '/api/v1/product/work-items?page=' + pages.work),
  outcomes = useData(() => '/api/v1/product/outcomes?page=' + pages.outcomes),
  funnel = useData('/api/v1/product/funnel');
watch(
  () => policy.data.value,
  (data) => {
    if (data) {
      model.value = JSON.parse(JSON.stringify(data.policy));
      faq.value = JSON.stringify(data.policy.faqs || {}, null, 2);
    }
  },
);
const fields = reactive({
  speech: [
    { key: 'name', label: '策略名称', required: true },
    { key: 'silence_seconds', label: '无应答等待（秒）', type: 'number', min: 3, max: 120 },
    { key: 'silence_prompt', label: '无应答提醒' },
    { key: 'wait_seconds', label: '客户要求稍等（秒）', type: 'number', min: 5, max: 180 },
    { key: 'confidence_threshold', label: '低于此置信度先确认', type: 'number', min: 0, max: 1 },
    { key: 'clarify_prompt', label: '听不清时的提示' },
    { key: 'max_clarifications', label: '最多澄清次数', type: 'number', min: 1, max: 5 },
    {
      key: 'model_wait_seconds',
      label: '等待模型多久后提示（秒）',
      type: 'number',
      min: 1,
      max: 20,
    },
    { key: 'model_wait_prompt', label: '模型等待提示' },
    { key: 'failure_prompt', label: '服务失败结束语' },
    {
      key: 'machine_action',
      label: '电话助手或信箱提示',
      options: [
        { value: 'confirm', label: '先确认是否真人，再决定结束' },
        { value: 'end', label: '识别到提示后结束' },
      ],
    },
    { key: 'allow_interruptions', label: '允许客户打断', type: 'boolean' },
    { key: 'min_interrupt_chars', label: '有效打断最少字数', type: 'number', min: 1, max: 10 },
    { key: 'opening_delay_ms', label: '开场延迟（毫秒）', type: 'number', min: 0, max: 5000 },
    { key: 'sentence_silence_ms', label: '断句静默（毫秒）', type: 'number', min: 200, max: 2000 },
    { key: 'language', label: '语言', options: ['zh-CN', 'en-US', 'zh-HK'] },
    { key: 'voice', label: '已配置的音色' },
    { key: 'vocabulary_id', label: '行业热词表编号' },
  ],
  handoff: [
    { key: 'handoff_team_ids', label: '可接待团队', multiple: true, options: [] },
    {
      key: 'handoff_skills',
      label: '所需技能（如城市、产品、语言）',
      multiple: true,
      tags: true,
      options: [],
    },
    { key: 'handoff_capacity_control', label: '按人工承接能力控制拨号', type: 'boolean' },
    { key: 'handoff_queue_limit', label: '人工最大等待人数', type: 'number', min: 1, max: 500 },
    { key: 'handoff_expected_ratio', label: '预计转人工比例', type: 'number', min: 0.01, max: 1 },
    { key: 'handoff_auto_callback', label: '无人接待时创建人工回拨待办', type: 'boolean' },
    {
      key: 'handoff_agent_ids',
      label: '可接待的坐席编号',
      multiple: true,
      tags: true,
      options: [],
    },
    { key: 'handoff_wait_seconds', label: '最长等待（秒）', type: 'number', min: 5, max: 300 },
    { key: 'handoff_prompt', label: '等待提示' },
    { key: 'handoff_timeout_prompt', label: '等待超时提示' },
    {
      key: 'handoff_fallback',
      label: '超时处理',
      options: [
        { value: 'end', label: '说明后结束通话' },
        { value: 'ai', label: '返回 AI 继续沟通' },
      ],
    },
    { key: 'timezone', label: '服务时区' },
    { key: 'start_hour', label: '开始时刻', type: 'number', min: 0, max: 23 },
    { key: 'end_hour', label: '结束时刻', type: 'number', min: 1, max: 24 },
    {
      key: 'weekdays',
      label: '工作日',
      multiple: true,
      options: ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日'].map(
        (label, value) => ({ value, label }),
      ),
    },
    { key: 'holidays', label: '不服务日期（年-月-日）', multiple: true, tags: true, options: [] },
  ],
});
watch(
  () => routingTeams.data.value,
  (rows) => {
    fields.handoff[0].options = (rows || [])
      .filter((t) => t.enabled)
      .map((t) => ({ value: t.id, label: t.name }));
  },
);
const slotFields = [
  { key: 'key', label: '字段标识', required: true, pattern: /^[a-z][a-z0-9_]{0,39}$/ },
  { key: 'label', label: '名称', required: true },
  {
    key: 'kind',
    label: '类型',
    options: [
      { value: 'text', label: '文本' },
      { value: 'integer', label: '整数' },
      { value: 'choice', label: '选项' },
      { value: 'date', label: '日期' },
      { value: 'digits', label: '按键数字' },
    ],
  },
  { key: 'required', label: '必填', type: 'boolean' },
  { key: 'confirm', label: '复述确认', type: 'boolean' },
  { key: 'question', label: '询问话术', required: true, wide: true },
  { key: 'choices', label: '可选值', options: [], multiple: true, tags: true },
  { key: 'qualifies', label: '合格取值', options: [], multiple: true, tags: true },
];
const errors = computed(() =>
  [policy, appointments, work, outcomes, funnel].map((q) => q.error.value).filter(Boolean),
);
function refresh() {
  [policy, appointments, work, outcomes, funnel].forEach((q) => q.load());
}
async function validatedPolicy() {
  for (const form of forms.value.filter(Boolean)) await form.validate();
  return {
    ...model.value,
    faqs: JSON.parse(faq.value),
    handoff_agent_ids: (model.value.handoff_agent_ids || []).map(Number),
    handoff_team_ids: (model.value.handoff_team_ids || []).map(Number),
  };
}
async function publish() {
  busy.value = true;
  try {
    const current = await validatedPolicy();
    await action(
      () =>
        api('/policy', 'POST', {
          campaign_id: campaign.value || null,
          expected_version_id: policy.data.value?.version_id ?? null,
          policy: { ...policy.data.value?.policy, ...current },
        }),
      { success: '策略已发布，下次通话生效', refresh },
    );
  } finally {
    busy.value = false;
  }
}
async function simulate() {
  const current = await validatedPolicy();
  probe.value = await action(
    () =>
      api('/simulate', 'POST', {
        policy: { ...policy.data.value?.policy, ...current },
        utterances: sample.value.split('\n').filter(Boolean),
      }),
    { success: '' },
  );
}
async function appointment(row, cancel) {
  await action(
    () =>
      api('/appointments/' + row.id, 'PATCH', {
        revision: row.revision,
        cancel,
        ...(!cancel ? { scheduled_at: new Date(times[row.id]).toISOString() } : {}),
      }),
    { confirm: cancel ? '取消这次预约？' : undefined, refresh },
  );
}
async function upload(event) {
  const file = event.target.files?.[0];
  if (!file) return;
  if (file.size > 150000) return ElMessage.error('文件过大，请分段导入');
  knowledge.title = file.name;
  knowledge.content = await file.text();
  event.target.value = '';
}
function detail(value) {
  try {
    const d = JSON.parse(value);
    return d.text || d.error || d.appointment_id || '-';
  } catch {
    return '-';
  }
}
</script>
<template>
  <PageHeader title="业务交付策略" /><el-alert
    v-for="error in errors"
    :key="error"
    :title="error"
    type="error"
  /><el-card
    ><div class="toolbar">
      <span>活动编号（留空为租户默认）</span
      ><el-input-number v-model="campaign" aria-label="活动编号" :min="1" /><el-tag
        >当前版本 {{ policy.data.value?.version_id || '默认' }}</el-tag
      ><el-button @click="refresh">刷新</el-button>
    </div>
    <el-tabs
      ><el-tab-pane label="对话与异常处理"
        ><FieldForm
          v-if="policy.data.value"
          :ref="(el) => (forms[0] = el)"
          :model="model"
          :fields="fields.speech"
          hide-submit />
        <p>全局问答：每个问题对应一段回答，回答后返回尚未完成的采集问题。</p>
        <el-input
          v-model="faq"
          aria-label="全局问答"
          type="textarea"
          :rows="4"
          placeholder='{"你们是谁":"我们是客服团队。"}' /></el-tab-pane
      ><el-tab-pane label="信息采集"
        ><p>字段需要确认后才计入完整信息；合格取值为空时，仅表示信息已采集，不自动判为合格线索。</p>
        <el-card v-for="(slot, index) in model.slots || []" :key="index"
          ><template #header
            ><el-button type="danger" @click="model.slots.splice(index, 1)"
              >删除字段</el-button
            ></template
          ><FieldForm
            :ref="
              (el) => {
                if (el) forms[index + 2] = el;
              }
            "
            :model="slot"
            :fields="slotFields"
            hide-submit /></el-card
        ><el-button
          @click="
            model.slots.push({
              key: '',
              label: '',
              question: '',
              kind: 'text',
              required: true,
              confirm: true,
              choices: [],
              qualifies: [],
            })
          "
          >添加字段</el-button
        ></el-tab-pane
      ><el-tab-pane label="转人工与时间安排"
        ><FieldForm
          v-if="policy.data.value"
          :ref="(el) => (forms[1] = el)"
          :model="model"
          :fields="fields.handoff"
          hide-submit />
        <div class="form-grid">
          <el-form-item
            v-for="[key, label] in [
              ['busy', '忙线'],
              ['no_answer', '无人接听'],
              ['voicemail', '信箱'],
              ['failed', '临时失败'],
            ]"
            :key="key"
            :label="label + '重拨间隔（秒）'"
            ><el-input-number
              v-if="model.retry_seconds"
              v-model="model.retry_seconds[key]"
              :min="60"
              :max="604800"
          /></el-form-item></div></el-tab-pane
      ><el-tab-pane label="试跑与版本"
        ><p>
          每行输入一轮客户回答。试跑使用实际业务规则，不拨号、不调用模型、不发送短信；需要模型理解的步骤会明确标出。
        </p>
        <el-input v-model="sample" aria-label="客户对话样例" type="textarea" :rows="6" /><el-button
          @click="simulate"
          >运行对话试跑</el-button
        ><DataGrid
          :rows="probe?.trace"
          :columns="[
            { key: 'utterance', label: '客户说法' },
            { key: 'intent', label: '意图' },
            { key: 'action', label: '动作' },
            { key: 'reply', label: '回复' },
          ]"
          row-key="utterance"
        /><DataGrid
          :pagination="10"
          :rows="policy.data.value?.history"
          :columns="[
            { key: 'id', label: '版本' },
            { key: 'created_at', label: '发布时间', date: true },
          ]"
          ><template #actions="{ row }"
            ><el-button
              @click="
                model = JSON.parse(JSON.stringify(row.policy));
                faq = JSON.stringify(row.policy.faqs || {}, null, 2);
                ElMessage.info('旧版已载入，发布后成为新版本');
              "
              >载入此版</el-button
            ></template
          ></DataGrid
        ></el-tab-pane
      ></el-tabs
    ><el-button type="primary" :loading="busy" :disabled="!policy.data.value" @click="publish"
      >发布策略</el-button
    ></el-card
  ><el-card
    ><el-tabs
      ><el-tab-pane label="预约回拨"
        ><DataGrid
          :rows="appointments.data.value"
          :columns="[
            { key: 'phone', label: '号码' },
            { key: 'scheduled_at', label: '预约时间', date: true },
            { key: 'state', label: '状态' },
          ]"
          ><template #actions="{ row }"
            ><el-input
              v-model="times[row.id]"
              type="datetime-local"
              :aria-label="'修改预约' + row.id"
              :disabled="row.state !== 'confirmed'"
            /><el-button
              :disabled="row.state !== 'confirmed' || !times[row.id]"
              @click="appointment(row, false)"
              >保存时间</el-button
            ><el-button
              type="danger"
              :disabled="row.state !== 'confirmed'"
              @click="appointment(row, true)"
              >取消预约</el-button
            ></template
          ></DataGrid
        ><Pager
          :page="pages.appointments"
          :count="appointments.data.value?.length || 0"
          @change="pages.appointments = $event" /></el-tab-pane
      ><el-tab-pane label="人工跟进"
        ><DataGrid
          :rows="work.data.value"
          :columns="[
            { key: 'phone', label: '号码' },
            { key: 'kind', label: '来源' },
            { key: 'state', label: '状态' },
            { key: 'detail_json', label: '内容', render: detail },
          ]"
          ><template #actions="{ row }"
            ><el-button
              :disabled="row.state === 'completed'"
              @click="
                action(() => api('/work-items/' + row.id, 'PATCH', { state: 'completed' }), {
                  refresh,
                })
              "
              >标记完成</el-button
            ></template
          ></DataGrid
        ><Pager
          :page="pages.work"
          :count="work.data.value?.length || 0"
          @change="pages.work = $event" /></el-tab-pane
      ><el-tab-pane label="交付统计"
        ><p>
          最近 7 天接通尝试 {{ funnel.data.value?.answered_attempts ?? '-' }} 次，接通号码
          {{ funnel.data.value?.answered_phones ?? '-' }} 个。
        </p>
        <p>{{ funnel.data.value?.definitions }}</p>
        <DataGrid
          :pagination="10"
          :rows="funnel.data.value?.groups"
          :columns="[
            { key: 'outcome', label: '业务结果' },
            { key: 'answer_kind', label: '接听类型' },
            { key: 'attempts', label: '尝试次数' },
            { key: 'unique_phones', label: '组内唯一号码' },
          ]"
          row-key="outcome" /></el-tab-pane
      ><el-tab-pane label="交付结果"
        ><DataGrid
          :rows="outcomes.data.value"
          row-key="call_id"
          :columns="[
            { key: 'phone', label: '号码' },
            { key: 'attempt', label: '拨打轮次' },
            { key: 'policy_version_id', label: '策略版本' },
            { key: 'data.answer_kind', label: '接听类型' },
            { key: 'data.outcome', label: '业务结果' },
            {
              key: 'data.slots',
              label: '已采集信息',
              render: (value) =>
                Object.entries(value || {})
                  .map(
                    ([key, slot]) => key + '：' + slot.value + (slot.confirmed ? '（已确认）' : ''),
                  )
                  .join('；'),
            },
          ]" /><Pager
          :page="pages.outcomes"
          :count="outcomes.data.value?.length || 0"
          @change="pages.outcomes = $event" /></el-tab-pane
      ><el-tab-pane label="知识导入与检索测试"
        ><el-input v-model="knowledge.title" aria-label="知识标题" placeholder="知识标题" /><input
          type="file"
          accept=".txt,.md"
          aria-label="导入文本文件"
          @change="upload" /><el-input
          v-model="knowledge.source"
          aria-label="知识出处"
          placeholder="知识出处或文档编号" /><el-input
          v-model="knowledge.content"
          aria-label="知识正文"
          type="textarea"
          :rows="5" /><el-button
          :disabled="!knowledge.title || !knowledge.content"
          @click="
            action(() =>
              api('/knowledge/import', 'POST', { ...knowledge, campaign_id: campaign || null }),
            ).then(() => (knowledge.content = ''))
          "
          >保存知识</el-button
        ><el-input v-model="search" aria-label="检索问题" /><el-button
          :disabled="!search"
          @click="
            action(
              () =>
                api('/knowledge/search', 'POST', { query: search, campaign_id: campaign || null }),
              { success: '' },
            ).then((value) => (hits = value))
          "
          >测试检索</el-button
        ><DataGrid
          :pagination="10"
          :rows="hits"
          :columns="[
            { key: 'title', label: '标题' },
            { key: 'content', label: '匹配内容' },
          ]" /></el-tab-pane></el-tabs
  ></el-card>
</template>
