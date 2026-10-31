<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';
import { request } from '../session';
import { action } from '../data';
import { formatDate } from '../api';
import { followLabels, reasonLabels, resultLabels, disputeLabels } from '../client/labels';
const props = defineProps({ clientId: { type: Number, required: true } });
const base = computed(() => '/api/v1/ops/clients/' + props.clientId),
  tab = ref('info'),
  org = ref(null),
  orgForm = reactive({}),
  config = ref(null),
  bindings = ref([]),
  campaigns = ref([]),
  bindOpen = ref(false),
  bindModel = reactive({ campaign_ids: [], deliver_history: false }),
  users = ref([]),
  userOpen = ref(false),
  userModel = reactive({ username: '', full_name: '', phone: '', role: 'owner' }),
  leadFilter = reactive({ status: '', follow_state: '', dispute_state: '' }),
  leads = ref({ items: [], total: 0, next_cursor: null }),
  lead = ref(null),
  stats = ref(null),
  agents = ref([]),
  staff = ref([]),
  busy = ref(false);
async function loadOrg() {
  org.value = await request(base.value);
  Object.assign(orgForm, org.value);
}
async function loadTab(name = tab.value) {
  try {
    if (name === 'info') await loadOrg();
    if (name === 'settings') config.value = await request(base.value + '/settings');
    if (name === 'campaigns') {
      bindings.value = await request(base.value + '/campaigns');
      campaigns.value = (await request('/api/v1/campaigns?page=1&size=200')).filter(
        (c) => c.status !== 'deleted',
      );
    }
    if (name === 'users') users.value = await request(base.value + '/users');
    if (name === 'leads') await loadLeads();
    if (name === 'stats') stats.value = await request(base.value + '/stats?days=30');
    if (name === 'staff') {
      staff.value = (await request(base.value + '/staff')).map((s) => s.user_id);
      if (org.value?.can_manage_org)
        agents.value = (await request('/api/v1/admin/users?page=1&size=200')).filter(
          (u) => u.role === 'agent',
        );
    }
  } catch (e) {
    ElMessage.error(e.message);
  }
}
onMounted(() => loadOrg().catch((e) => ElMessage.error(e.message)));
watch(tab, (name) => loadTab(name));

async function saveOrg() {
  await action(() => request(base.value, 'PUT', orgForm), { refresh: loadOrg });
}
async function saveSettings() {
  await action(() => request(base.value + '/settings', 'PUT', config.value), {
    refresh: () => loadTab('settings'),
  });
}

const unbound = computed(() => {
  const bound = new Set(bindings.value.map((b) => b.campaign_id));
  return campaigns.value.filter((c) => !bound.has(c.id));
});
async function bind() {
  busy.value = true;
  try {
    const result = await action(() => request(base.value + '/campaigns', 'POST', bindModel), {
      refresh: () => loadTab('campaigns'),
    });
    const moved = result.results.filter((r) => r.backfill?.processed).length;
    if (moved) ElMessage.info(`已回溯 ${moved} 个任务的历史质检结果`);
    bindOpen.value = false;
  } finally {
    busy.value = false;
  }
}
function unbind(row) {
  return action(() => request(base.value + '/campaigns/' + row.campaign_id, 'DELETE'), {
    confirm: `解绑任务「${row.campaign_name}」？已交付的线索保留，之后的线索不再交付给该企业。`,
    refresh: () => loadTab('campaigns'),
  });
}
async function backfill(row) {
  await ElMessageBox.confirm(
    `按当前交付配置重新检查任务「${row.campaign_name}」的全部质检结果？已交付的不会重复。`,
    '回溯交付',
  );
  let after = 0,
    total = 0;
  try {
    for (;;) {
      const r = await request(base.value + '/backfill', 'POST', {
        campaign_id: row.campaign_id,
        after_id: after,
      });
      total += r.processed;
      after = r.last_id;
      if (!r.has_more) break;
    }
    ElMessage.success(`回溯完成，检查了 ${total} 条质检结果`);
  } catch (e) {
    ElMessage.error(e.message);
  }
}

function showPassword(user) {
  ElMessageBox.alert(
    `账号 ${user.username} 的初始密码：${user.initial_password}\n关闭后不再显示，请立即转交企业并提醒首次登录修改。`,
    '一次性密码',
    { type: 'warning' },
  );
}
async function createUser() {
  busy.value = true;
  try {
    const user = await action(() => request(base.value + '/users', 'POST', userModel), {
      refresh: () => loadTab('users'),
    });
    userOpen.value = false;
    Object.assign(userModel, { username: '', full_name: '', phone: '', role: 'owner' });
    showPassword(user);
  } finally {
    busy.value = false;
  }
}
async function userAction(row, kind, extra = {}) {
  const labels = { reset_password: '重置密码', revoke: '强制下线', update: '保存' };
  const user = await action(
    () => request(base.value + '/users/' + row.id, 'PUT', { action: kind, ...extra }),
    {
      confirm: kind === 'update' ? undefined : `确认${labels[kind]}账号 ${row.username}？`,
      refresh: () => loadTab('users'),
    },
  );
  if (user.initial_password) showPassword(user);
}

async function loadLeads(cursor) {
  const params = new URLSearchParams({ limit: '20' });
  for (const [k, v] of Object.entries(leadFilter)) if (v) params.set(k, v);
  if (cursor) params.set('cursor', cursor);
  const page = await request(base.value + '/leads?' + params);
  leads.value = cursor ? { ...page, items: [...leads.value.items, ...page.items] } : page;
}
async function openLead(row) {
  lead.value = await request(base.value + '/leads/' + row.id);
}
async function withdraw() {
  const { value } = await ElMessageBox.prompt(
    '撤回后企业侧显示为“已撤回”。请填写原因',
    '撤回线索',
    {
      inputValidator: (v) => !!v?.trim() || '请填写原因',
    },
  );
  lead.value = await action(
    () => request(base.value + '/leads/' + lead.value.id + '/withdraw', 'POST', { note: value }),
    { refresh: () => loadLeads() },
  );
}
async function decide(decision) {
  const { value } = await ElMessageBox.prompt(
    decision === 'accepted' ? '申诉成立：该线索不计入有效交付' : '驳回申诉',
    '审核申诉',
    { inputValidator: (v) => !!v?.trim() || '请填写审核说明' },
  );
  lead.value = await action(
    () =>
      request(base.value + '/leads/' + lead.value.id + '/dispute', 'PUT', {
        decision,
        note: value,
      }),
    { refresh: () => loadLeads() },
  );
}
async function saveStaff() {
  await action(() => request(base.value + '/staff', 'PUT', { user_ids: staff.value }), {
    refresh: () => loadTab('staff'),
  });
}
const eventLabels = {
  delivered: '交付',
  redelivered: '重新交付',
  refreshed: '质检结果更新',
  withdrawn: '撤回',
  viewed: '企业首次查看',
  phone_revealed: '查看完整号码',
  recording_played: '播放录音',
  follow: '跟进反馈',
  assigned: '分配',
  dispute_submitted: '提交申诉',
  dispute_accepted: '申诉成立',
  dispute_rejected: '申诉驳回',
};
</script>
<template>
  <div v-if="org" class="client-detail">
    <div class="actions">
      <h2>{{ org.name }}</h2>
      <el-tag :type="org.active ? 'success' : 'danger'">{{
        org.active ? '服务中' : org.status === 'disabled' ? '已停用' : '不在合同期'
      }}</el-tag>
      <span class="muted">企业门户登录地址：/client/login</span>
    </div>
    <el-tabs v-model="tab">
      <el-tab-pane label="基本信息" name="info">
        <el-form label-width="110px" style="max-width: 640px">
          <el-form-item label="企业编码"
            ><el-input :model-value="org.code" disabled
          /></el-form-item>
          <el-form-item label="企业名称"><el-input v-model="orgForm.name" /></el-form-item>
          <el-form-item label="联系人"><el-input v-model="orgForm.contact_name" /></el-form-item>
          <el-form-item label="联系电话"><el-input v-model="orgForm.contact_phone" /></el-form-item>
          <el-form-item label="合同期"
            ><el-input v-model="orgForm.contract_start" type="date" style="width: 46%" /> 至
            <el-input v-model="orgForm.contract_end" type="date" style="width: 46%"
          /></el-form-item>
          <el-form-item label="状态"
            ><el-radio-group v-model="orgForm.status"
              ><el-radio value="active">启用</el-radio
              ><el-radio value="disabled">停用（账号立即不能登录）</el-radio></el-radio-group
            ></el-form-item
          >
          <el-form-item label="备注"
            ><el-input v-model="orgForm.remark" type="textarea" :rows="3"
          /></el-form-item>
          <el-form-item
            ><el-button type="primary" :disabled="!org.can_manage_org" @click="saveOrg"
              >保存</el-button
            ><span v-if="!org.can_manage_org" class="muted">仅平台管理员可修改</span></el-form-item
          >
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="交付配置" name="settings">
        <el-form v-if="config" label-width="150px" style="max-width: 720px">
          <el-form-item label="交付结果">
            <el-checkbox-group v-model="config.deliver_results"
              ><el-checkbox v-for="(label, key) in resultLabels" :key="key" :value="key">{{
                label
              }}</el-checkbox></el-checkbox-group
            >
          </el-form-item>
          <el-form-item label="自动质检直接交付"
            ><el-switch v-model="config.auto_deliver" /><span class="muted"
              >关闭时只交付人工复核通过的线索</span
            ></el-form-item
          >
          <el-form-item label="允许查看完整号码"
            ><el-switch v-model="config.phone_reveal" /><span class="muted"
              >每次查看都会记录审计</span
            ></el-form-item
          >
          <el-form-item label="企业可见内容">
            <el-checkbox v-model="config.visible.summary">AI 摘要</el-checkbox>
            <el-checkbox v-model="config.visible.slots">采集字段</el-checkbox>
            <el-checkbox v-model="config.visible.transcript">通话转写</el-checkbox>
            <el-checkbox v-model="config.visible.recording">录音</el-checkbox>
          </el-form-item>
          <el-form-item label="去重窗口（天）"
            ><el-input-number v-model="config.dedup_days" :min="0" :max="365" /><span class="muted"
              >0 表示不去重；同企业同号码窗口内只交付一次</span
            ></el-form-item
          >
          <el-form-item label="申诉期（天）"
            ><el-input-number v-model="config.dispute_days" :min="0" :max="90"
          /></el-form-item>
          <el-form-item label="允许导出"
            ><el-switch v-model="config.export.enabled" /> <span class="muted">每日上限</span
            ><el-input-number v-model="config.export.daily_limit" :min="0" :max="100000" /><span
              class="muted"
              >0 表示单次最多 10000 条</span
            ></el-form-item
          >
          <el-form-item
            ><el-button type="primary" @click="saveSettings">保存配置</el-button
            ><span class="muted">当前版本 v{{ config.revision }}</span></el-form-item
          >
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="绑定任务" name="campaigns">
        <div class="actions">
          <el-button type="primary" :disabled="org.status !== 'active'" @click="bindOpen = true"
            >绑定任务</el-button
          ><span class="muted">一个任务只归属一个企业；改绑只影响之后的交付</span>
        </div>
        <DataGrid
          :rows="bindings"
          row-key="campaign_id"
          :columns="[
            { key: 'campaign_id', label: '任务 ID' },
            { key: 'campaign_name', label: '任务名称' },
            { key: 'campaign_status', label: '任务状态', translate: true },
            { key: 'bound_at', label: '绑定时间', date: true },
          ]"
          ><template #actions="{ row }"
            ><el-button size="small" @click="backfill(row)">回溯交付</el-button
            ><el-button size="small" type="danger" @click="unbind(row)">解绑</el-button></template
          ></DataGrid
        >
        <el-dialog v-model="bindOpen" title="绑定任务" width="560px" append-to-body>
          <el-select
            v-model="bindModel.campaign_ids"
            multiple
            filterable
            placeholder="选择未绑定或需改绑的任务"
            style="width: 100%"
            ><el-option
              v-for="c in unbound"
              :key="c.id"
              :label="`#${c.id} ${c.name}`"
              :value="c.id"
          /></el-select>
          <p>
            <el-checkbox v-model="bindModel.deliver_history"
              >同时交付这些任务此前已质检通过的线索</el-checkbox
            >
          </p>
          <template #footer
            ><el-button @click="bindOpen = false">取消</el-button
            ><el-button
              type="primary"
              :loading="busy"
              :disabled="!bindModel.campaign_ids.length"
              @click="bind"
              >确认绑定</el-button
            ></template
          >
        </el-dialog>
      </el-tab-pane>

      <el-tab-pane label="企业账号" name="users">
        <div class="actions">
          <el-button type="primary" @click="userOpen = true">开通账号</el-button>
        </div>
        <DataGrid
          :rows="users"
          :columns="[
            { key: 'username', label: '用户名' },
            { key: 'full_name', label: '姓名' },
            { key: 'role', label: '角色', render: (v) => (v === 'owner' ? '企业管理员' : '成员') },
            { key: 'enabled', label: '启用' },
            { key: 'must_change_password', label: '待首次改密' },
            { key: 'last_login_at', label: '最近登录', date: true },
          ]"
          ><template #actions="{ row }"
            ><el-button size="small" @click="userAction(row, 'reset_password')">重置密码</el-button
            ><el-button size="small" @click="userAction(row, 'revoke')">强制下线</el-button
            ><el-button
              size="small"
              :type="row.enabled ? 'danger' : 'success'"
              @click="userAction(row, 'update', { enabled: !row.enabled })"
              >{{ row.enabled ? '停用' : '启用' }}</el-button
            ></template
          ></DataGrid
        >
        <el-dialog v-model="userOpen" title="开通企业账号" width="520px" append-to-body>
          <el-form label-width="90px">
            <el-form-item label="用户名" required
              ><el-input v-model="userModel.username" aria-label="账号用户名" autocomplete="off"
            /></el-form-item>
            <el-form-item label="姓名" required
              ><el-input v-model="userModel.full_name" aria-label="账号姓名"
            /></el-form-item>
            <el-form-item label="手机号"
              ><el-input v-model="userModel.phone" aria-label="账号手机号"
            /></el-form-item>
            <el-form-item label="角色"
              ><el-radio-group v-model="userModel.role"
                ><el-radio value="owner">企业管理员</el-radio
                ><el-radio value="member">成员</el-radio></el-radio-group
              ></el-form-item
            >
          </el-form>
          <p class="muted">系统生成一次性初始密码，仅显示一次；企业首次登录须修改密码。</p>
          <template #footer
            ><el-button @click="userOpen = false">取消</el-button
            ><el-button type="primary" :loading="busy" @click="createUser"
              >开通</el-button
            ></template
          >
        </el-dialog>
      </el-tab-pane>

      <el-tab-pane label="交付记录" name="leads">
        <div class="actions">
          <el-select
            v-model="leadFilter.status"
            clearable
            placeholder="交付状态"
            style="width: 130px"
            ><el-option label="已交付" value="delivered" /><el-option
              label="已撤回"
              value="withdrawn"
          /></el-select>
          <el-select
            v-model="leadFilter.follow_state"
            clearable
            placeholder="跟进状态"
            style="width: 130px"
            ><el-option v-for="(l, k) in followLabels" :key="k" :label="l" :value="k"
          /></el-select>
          <el-select
            v-model="leadFilter.dispute_state"
            clearable
            placeholder="申诉"
            style="width: 130px"
            ><el-option v-for="(l, k) in disputeLabels" :key="k" :label="l" :value="k"
          /></el-select>
          <el-button @click="loadLeads()">查询</el-button>
          <span class="muted">共 {{ leads.total }} 条</span>
        </div>
        <DataGrid
          :rows="leads.items"
          :columns="[
            { key: 'delivered_at', label: '交付时间', date: true },
            { key: 'campaign_name', label: '任务' },
            { key: 'contact_name', label: '客户' },
            { key: 'phone', label: '号码' },
            { key: 'result_code', label: '结果', render: (v) => resultLabels[v] || v },
            {
              key: 'status',
              label: '交付',
              render: (v) => (v === 'delivered' ? '已交付' : '已撤回'),
            },
            { key: 'follow_state', label: '跟进', render: (v) => followLabels[v] },
            { key: 'dispute_state', label: '申诉', render: (v) => disputeLabels[v] },
          ]"
          ><template #actions="{ row }"
            ><el-button size="small" @click="openLead(row)">详情</el-button></template
          ></DataGrid
        >
        <el-button v-if="leads.next_cursor" link @click="loadLeads(leads.next_cursor)"
          >加载更多</el-button
        >
        <el-drawer
          :model-value="!!lead"
          size="46%"
          title="线索详情"
          append-to-body
          @close="lead = null"
        >
          <template v-if="lead">
            <el-descriptions :column="1" border>
              <el-descriptions-item label="客户">{{
                lead.contact_name || '-'
              }}</el-descriptions-item>
              <el-descriptions-item label="号码">{{ lead.phone }}</el-descriptions-item>
              <el-descriptions-item label="任务">{{ lead.campaign_name }}</el-descriptions-item>
              <el-descriptions-item label="结果">{{
                resultLabels[lead.result_code] || lead.result_code
              }}</el-descriptions-item>
              <el-descriptions-item label="交付状态"
                >{{ lead.status === 'delivered' ? '已交付' : '已撤回' }}
                {{ lead.withdraw_note || lead.withdraw_reason || '' }}</el-descriptions-item
              >
              <el-descriptions-item label="跟进"
                >{{ followLabels[lead.follow_state] }}
                {{ reasonLabels[lead.invalid_reason] || '' }}
                {{ lead.follow_note || '' }}</el-descriptions-item
              >
              <el-descriptions-item label="申诉"
                >{{ disputeLabels[lead.dispute_state] }}
                {{ lead.dispute_note || '' }}</el-descriptions-item
              >
              <el-descriptions-item label="摘要">{{ lead.summary }}</el-descriptions-item>
            </el-descriptions>
            <div class="actions">
              <el-button v-if="lead.status === 'delivered'" type="danger" @click="withdraw"
                >撤回线索</el-button
              >
              <template v-if="lead.dispute_state === 'submitted'"
                ><el-button type="warning" @click="decide('accepted')">申诉成立</el-button
                ><el-button @click="decide('rejected')">驳回申诉</el-button></template
              >
            </div>
            <h4>操作记录</h4>
            <el-timeline
              ><el-timeline-item
                v-for="e in lead.events"
                :key="e.id"
                :timestamp="formatDate(e.created_at)"
                >{{ eventLabels[e.action] || e.action }} ·
                {{ { client: '企业', staff: '平台', system: '系统' }[e.actor_kind] }}
                {{ e.detail?.note || e.detail?.reason || '' }}</el-timeline-item
              ></el-timeline
            >
          </template>
        </el-drawer>
      </el-tab-pane>

      <el-tab-pane label="交付看板" name="stats">
        <template v-if="stats">
          <div class="metric-row">
            <el-statistic title="累计交付" :value="Number(stats.summary.total)" />
            <el-statistic title="企业未读" :value="Number(stats.summary.unread)" />
            <el-statistic
              title="有效 + 成交"
              :value="Number(stats.summary.valid) + Number(stats.summary.won)"
            />
            <el-statistic title="无效" :value="Number(stats.summary.invalid)" />
            <el-statistic title="申诉中" :value="Number(stats.summary.disputing)" />
            <el-statistic title="有效率 %" :value="stats.valid_rate ?? 0" />
          </div>
          <h4>近 30 天按任务</h4>
          <DataGrid
            :rows="stats.campaigns"
            row-key="campaign_id"
            :columns="[
              { key: 'campaign_name', label: '任务' },
              { key: 'delivered', label: '交付' },
              { key: 'valid', label: '有效' },
              { key: 'invalid', label: '无效' },
              { key: 'disputes_accepted', label: '申诉成立' },
            ]"
          />
        </template>
      </el-tab-pane>

      <el-tab-pane label="交付专员" name="staff">
        <p class="muted">
          交付专员是被授权管理本企业的坐席账号：可开通账号、绑定任务、处理撤回与申诉，看不到其他企业。
        </p>
        <template v-if="org.can_manage_org">
          <el-select v-model="staff" multiple filterable style="width: 480px"
            ><el-option
              v-for="a in agents"
              :key="a.id"
              :label="`${a.full_name}（${a.username}）`"
              :value="a.id"
          /></el-select>
          <el-button type="primary" @click="saveStaff">保存</el-button>
        </template>
        <p v-else class="muted">仅平台管理员可调整交付专员。</p>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
