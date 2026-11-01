<script setup>
import { ref, computed } from 'vue';
import { request } from '../session';
import { useData, action } from '../data';
const base = '/api/v1/marketing/links';
const tab = ref('domains'),
  overview = useData(base + '/overview'),
  domains = useData(base + '/domains'),
  landing = useData(base + '/landing-domains'),
  dimension = ref('campaign'),
  days = ref(30),
  performance = useData(
    () => `${base}/performance?dimension=${dimension.value}&days=${days.value}`,
  ),
  clickCode = ref(''),
  clickVerdict = ref(''),
  clickQuery = useData(
    () =>
      `${base}/clicks?` +
      new URLSearchParams({ code: clickCode.value, verdict: clickVerdict.value, limit: '200' }),
  ),
  publicLinks = useData(base + '/public'),
  statusMap = useData(base + '/status-map');
const stateLabels = { active: '使用中', standby: '备用', blocked: '已拦截', disabled: '停用' },
  probeLabels = { ok: '正常', warn: '预警', failed: '异常' },
  verdictLabels = { valid: '有效', repeat: '重复（30 分钟内）', bot: '机器点击' },
  reasonLabels = {
    first: '首次点击',
    within_30m: '30 分钟内重复',
    no_ua: '无浏览器标识',
    scanner_ua: '已知扫描器',
    too_fast: '发送后 10 秒内',
  },
  dimensionLabels = { campaign: '活动', snapshot: '人群快照', template: '短信模板' };
const smsOutcomes = [
  'SMS_DELIVERED',
  'SMS_FAIL_INVALID',
  'SMS_FAIL_BLOCKED',
  'SMS_FAIL_TEMP',
  'SMS_SUBMIT_FAIL',
  'SMS_UNKNOWN',
];
const editing = ref(null),
  domainModel = ref({}),
  busy = ref(false);
const domainFields = computed(() => [
  {
    key: 'domain',
    label: '短链域名',
    required: true,
    disabled: !!editing.value?.id,
    hint: '例如 s.example.com，需已完成 ICP 备案',
  },
  { key: 'pool', label: '域名池', hint: '默认 default；发送时按池选择域名' },
  {
    key: 'state',
    label: '状态',
    options: Object.entries(stateLabels).map(([value, label]) => ({ value, label })),
  },
  { key: 'weight', label: '权重', type: 'number', min: 0, max: 10000 },
  { key: 'icp_number', label: 'ICP 备案号' },
  { key: 'cert_expires_at', label: '证书到期日', type: 'date' },
]);
function editDomain(row) {
  editing.value = row || {};
  domainModel.value = row
    ? {
        domain: row.domain,
        pool: row.pool,
        state: row.state,
        weight: row.weight,
        icp_number: row.icp_number,
        cert_expires_at: row.cert_expires_at ? String(row.cert_expires_at).slice(0, 10) : '',
      }
    : { pool: 'default', state: 'standby', weight: 100 };
}
async function saveDomain() {
  busy.value = true;
  try {
    const id = editing.value.id;
    await action(
      () =>
        request(
          id ? `${base}/domains/${id}` : `${base}/domains`,
          id ? 'PUT' : 'POST',
          domainModel.value,
        ),
      { refresh: domains.load },
    );
    editing.value = null;
    overview.load();
  } finally {
    busy.value = false;
  }
}
const probe = (row) =>
  action(() => request(`${base}/domains/${row.id}/probe`, 'POST'), {
    refresh: domains.load,
    success: '探测完成',
  });
const landingModel = ref({ domain: '', note: '' });
const addLanding = () =>
  action(() => request(`${base}/landing-domains`, 'POST', landingModel.value), {
    refresh: async () => {
      landingModel.value = { domain: '', note: '' };
      await landing.load();
    },
  });
const removeLanding = (row) =>
  action(
    () => request(`${base}/landing-domains?domain=${encodeURIComponent(row.domain)}`, 'DELETE'),
    { confirm: `移除后不能再为 ${row.domain} 生成短链，确认移除？`, refresh: landing.load },
  );
const publicModel = ref(null);
const publicFields = [
  { key: 'name', label: '链接名称', required: true },
  { key: 'campaign_id', label: '活动编号' },
  {
    key: 'landing_url',
    label: '落地页地址',
    required: true,
    wide: true,
    hint: '域名必须在落地页白名单内',
  },
  { key: 'pool', label: '域名池', hint: '留空使用 default' },
  { key: 'expires_at', label: '有效期至', type: 'date', hint: '留空为 30 天' },
];
async function savePublic() {
  busy.value = true;
  try {
    const created = await action(() => request(`${base}/public`, 'POST', publicModel.value), {
      refresh: publicLinks.load,
      success: '已生成公共链接',
    });
    publicModel.value = null;
    return created;
  } finally {
    busy.value = false;
  }
}
const mapModel = ref({ provider: '*', status_code: '', outcome: 'SMS_FAIL_TEMP', note: '' });
const saveMap = () =>
  action(() => request(`${base}/status-map`, 'PUT', mapModel.value), {
    refresh: statusMap.load,
  });
const removeMap = (row) =>
  action(
    () =>
      request(
        `${base}/status-map?` +
          new URLSearchParams({ provider: row.provider, status_code: row.status_code }),
        'DELETE',
      ),
    { confirm: '确认删除这条自定义映射？', refresh: statusMap.load },
  );
const pools = computed(() => overview.data.value?.pools || []),
  queue = computed(() => overview.data.value?.click_queue || {});
</script>
<template>
  <PageHeader
    title="短链与域名"
    description="管理短链域名池与落地页白名单，查看每条短信短链的送达、点击与转化"
  /><el-card
    ><div class="actions">
      <el-tag v-for="p in pools" :key="p.pool" type="info"
        >域名池 {{ p.pool }}：使用中 {{ p.active }} · 备用 {{ p.standby }} · 已拦截
        {{ p.blocked }}</el-tag
      ><el-tag v-if="!pools.length" type="warning">尚未配置短链域名，短信将无法生成短链</el-tag
      ><el-tag :type="queue.dropped ? 'danger' : 'success'"
        >点击队列 {{ queue.queued ?? 0 }}/{{ queue.capacity ?? 0 }}，已丢弃
        {{ queue.dropped ?? 0 }}</el-tag
      ><el-tag v-if="overview.data.value" type="info"
        >探测方式：{{
          overview.data.value.probe_mode === 'mock' ? '模拟' : '真实网络'
        }}，拦截检测：{{
          overview.data.value.interception_mode === 'mock'
            ? '模拟'
            : overview.data.value.interception_mode
        }}</el-tag
      >
    </div>
    <el-alert v-if="overview.error.value" :title="overview.error.value" type="error" /><el-tabs
      v-model="tab"
      ><el-tab-pane label="域名池" name="domains"
        ><div class="actions">
          <el-button type="primary" @click="editDomain(null)">添加域名</el-button
          ><el-button @click="domains.load()">刷新</el-button>
        </div>
        <el-alert v-if="domains.error.value" :title="domains.error.value" type="error" /><DataGrid
          :rows="domains.data.value || []"
          :loading="domains.loading.value"
          :pagination="20"
          :columns="[
            { key: 'domain', label: '域名' },
            { key: 'pool', label: '域名池' },
            { key: 'state', label: '状态', render: (v) => stateLabels[v] || v },
            { key: 'weight', label: '权重' },
            { key: 'icp_number', label: 'ICP 备案号', render: (v) => v || '-' },
            {
              key: 'cert_expires_at',
              label: '证书到期',
              render: (v) => (v ? String(v).slice(0, 10) : '-'),
            },
            {
              key: 'probe_state',
              label: '探测结果',
              render: (v, row) =>
                (probeLabels[v] || '未探测') + (row.probe_reason ? '：' + row.probe_reason : ''),
            },
            { key: 'probed_at', label: '最近探测', date: true },
            { key: 'link_count', label: '短链数' },
          ]"
          ><template #actions="{ row }"
            ><el-button size="small" @click="editDomain(row)">编辑</el-button
            ><el-button size="small" @click="probe(row)">立即探测</el-button></template
          ></DataGrid
        ></el-tab-pane
      ><el-tab-pane label="落地页白名单" name="landing"
        ><p class="hint">
          只有白名单内的落地页域名才能生成短链，防止短链被用作任意跳转。填写 *.example.com
          可放行全部子域名。
        </p>
        <div class="actions">
          <el-input
            v-model="landingModel.domain"
            placeholder="落地页域名，如 www.example.com"
            style="width: 260px"
          /><el-input
            v-model="landingModel.note"
            placeholder="备注"
            style="width: 200px"
          /><el-button type="primary" :disabled="!landingModel.domain" @click="addLanding"
            >加入白名单</el-button
          >
        </div>
        <el-alert v-if="landing.error.value" :title="landing.error.value" type="error" /><DataGrid
          :rows="landing.data.value || []"
          :loading="landing.loading.value"
          row-key="domain"
          :columns="[
            { key: 'domain', label: '落地页域名' },
            { key: 'note', label: '备注', render: (v) => v || '-' },
            { key: 'created_at', label: '添加时间', date: true },
          ]"
          ><template #actions="{ row }"
            ><el-button size="small" type="danger" @click="removeLanding(row)"
              >移除</el-button
            ></template
          ></DataGrid
        ></el-tab-pane
      ><el-tab-pane label="效果统计" name="performance"
        ><div class="actions">
          <el-radio-group v-model="dimension"
            ><el-radio-button v-for="(label, key) in dimensionLabels" :key="key" :value="key">{{
              '按' + label
            }}</el-radio-button></el-radio-group
          ><el-select v-model="days" style="width: 120px"
            ><el-option :value="7" label="近 7 天" /><el-option
              :value="30"
              label="近 30 天" /><el-option :value="90" label="近 90 天"
          /></el-select>
        </div>
        <p class="hint">
          点击率 = 有点击的短链数 ÷ 送达数（尚无送达回执时按发送数计算）。机器点击与 30
          分钟内的重复点击不计入有效点击。
        </p>
        <el-alert
          v-if="performance.error.value"
          :title="performance.error.value"
          type="error" /><DataGrid
          :rows="performance.data.value || []"
          :loading="performance.loading.value"
          row-key="dimension_key"
          :pagination="20"
          :columns="[
            {
              key: 'dimension_key',
              label: dimensionLabels[dimension],
              render: (v) => v || '未关联',
            },
            { key: 'sent', label: '发送' },
            { key: 'delivered', label: '送达' },
            { key: 'valid_clicks', label: '有效点击' },
            { key: 'bot_clicks', label: '机器点击' },
            { key: 'clicked_links', label: '点击人数' },
            { key: 'click_rate', label: '点击率', render: (v) => v + '%' },
          ]"
      /></el-tab-pane>
      <el-tab-pane label="点击明细" name="clicks"
        ><div class="actions">
          <el-input
            v-model="clickCode"
            clearable
            placeholder="短链编码"
            style="width: 160px"
          /><el-select v-model="clickVerdict" clearable placeholder="全部判定" style="width: 160px"
            ><el-option
              v-for="(label, key) in verdictLabels"
              :key="key"
              :value="key"
              :label="label" /></el-select
          ><el-button @click="clickQuery.load()">查询</el-button>
        </div>
        <el-alert
          v-if="clickQuery.error.value"
          :title="clickQuery.error.value"
          type="error" /><DataGrid
          :rows="clickQuery.data.value || []"
          :loading="clickQuery.loading.value"
          :pagination="20"
          :columns="[
            { key: 'clicked_at', label: '点击时间', date: true },
            { key: 'code', label: '短链' },
            { key: 'phone', label: '号码' },
            { key: 'verdict', label: '判定', render: (v) => verdictLabels[v] || v },
            { key: 'reason', label: '原因', render: (v) => reasonLabels[v] || v || '-' },
            { key: 'wechat', label: '微信内打开' },
            { key: 'ip_prefix', label: 'IP 段' },
            { key: 'domain', label: '域名' },
            { key: 'snapshot_id', label: '人群快照', render: (v) => v || '-' },
          ]"
      /></el-tab-pane>
      <el-tab-pane label="活动公共链接" name="public"
        ><div class="actions">
          <el-button type="primary" @click="publicModel = {}">生成公共链接</el-button>
        </div>
        <p class="hint">
          公共链接面向整个活动投放，只统计访问次数（PV）与访客数（UV），不记录个人点击。
        </p>
        <el-alert
          v-if="publicLinks.error.value"
          :title="publicLinks.error.value"
          type="error" /><DataGrid
          :rows="publicLinks.data.value || []"
          :loading="publicLinks.loading.value"
          row-key="code"
          :pagination="20"
          :columns="[
            { key: 'name', label: '名称' },
            { key: 'campaign_id', label: '活动编号', render: (v) => v || '-' },
            { key: 'url', label: '短链' },
            { key: 'target_url', label: '落地页' },
            { key: 'pv', label: 'PV' },
            { key: 'uv', label: 'UV' },
            { key: 'expires_at', label: '有效期至', date: true },
          ]"
      /></el-tab-pane>
      <el-tab-pane label="回执映射" name="status"
        ><p class="hint">
          通道状态报告按此表转换为触达结果。自定义映射优先于平台默认；“*”表示适用于所有通道。超过 72
          小时仍无最终回执的营销短信记为“状态未知”。
        </p>
        <div class="actions">
          <el-input v-model="mapModel.provider" placeholder="通道" style="width: 100px" /><el-input
            v-model="mapModel.status_code"
            placeholder="状态码，如 MK:0001"
            style="width: 180px"
          /><el-select v-model="mapModel.outcome" style="width: 200px"
            ><el-option v-for="o in smsOutcomes" :key="o" :value="o" :label="o" /></el-select
          ><el-input v-model="mapModel.note" placeholder="说明" style="width: 200px" /><el-button
            type="primary"
            :disabled="!mapModel.status_code"
            @click="saveMap"
            >保存映射</el-button
          >
        </div>
        <el-alert
          v-if="statusMap.error.value"
          :title="statusMap.error.value"
          type="error"
        /><DataGrid
          :rows="statusMap.data.value || []"
          :loading="statusMap.loading.value"
          :row-key="(row) => row.provider + row.status_code + row.custom"
          :pagination="20"
          :columns="[
            { key: 'custom', label: '来源', render: (v) => (Number(v) ? '自定义' : '平台默认') },
            { key: 'provider', label: '通道' },
            { key: 'status_code', label: '状态码' },
            { key: 'outcome', label: '触达结果' },
            { key: 'note', label: '说明', render: (v) => v || '-' },
          ]"
          ><template #actions="{ row }"
            ><el-button v-if="Number(row.custom)" size="small" type="danger" @click="removeMap(row)"
              >删除</el-button
            ></template
          ></DataGrid
        ></el-tab-pane
      ></el-tabs
    ></el-card
  ><el-dialog
    :model-value="editing !== null"
    :title="editing?.id ? '编辑域名' : '添加域名'"
    width="640px"
    destroy-on-close
    @close="editing = null"
    ><FieldForm
      :model="domainModel"
      :fields="domainFields"
      :busy="busy"
      @submit="saveDomain" /></el-dialog
  ><el-dialog
    :model-value="publicModel !== null"
    title="生成活动公共链接"
    width="640px"
    destroy-on-close
    @close="publicModel = null"
    ><FieldForm
      v-if="publicModel"
      :model="publicModel"
      :fields="publicFields"
      :busy="busy"
      @submit="savePublic"
  /></el-dialog>
</template>
