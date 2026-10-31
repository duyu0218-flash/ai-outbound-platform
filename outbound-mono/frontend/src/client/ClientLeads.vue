<script setup>
import { ref, reactive, computed, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { formatDate } from '../api';
import { clientSession, clientRequest, clientDownload } from './session';
import { followLabels, reasonLabels, resultLabels, disputeLabels, withdrawLabels } from './labels';
const abilities = computed(() => clientSession.user?.abilities || {}),
  summary = ref({}),
  filter = reactive({
    from: '',
    to: '',
    follow_state: '',
    status: 'delivered',
    keyword: '',
    assignee: '',
  }),
  page = ref({ items: [], total: 0, next_cursor: null }),
  loading = ref(false),
  selected = ref([]),
  members = ref([]),
  lead = ref(null),
  phone = ref(''),
  transcript = ref(null),
  follow = reactive({
    follow_state: 'contacted',
    invalid_reason: '',
    note: '',
    next_follow_at: '',
  });
function params(cursor) {
  const p = new URLSearchParams({ limit: '20' });
  for (const [k, v] of Object.entries(filter)) if (v && k !== 'assignee') p.set(k, v);
  if (filter.assignee === 'none') p.set('unassigned', 'true');
  if (cursor) p.set('cursor', cursor);
  return p;
}
async function load(cursor) {
  loading.value = true;
  try {
    const [data, counts] = await Promise.all([
      clientRequest('/api/v1/client/leads?' + params(cursor)),
      cursor ? Promise.resolve(summary.value) : clientRequest('/api/v1/client/leads/summary'),
    ]);
    page.value = cursor ? { ...data, items: [...page.value.items, ...data.items] } : data;
    summary.value = counts;
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    loading.value = false;
  }
}
onMounted(async () => {
  await load();
  if (abilities.value.assign) members.value = await clientRequest('/api/v1/client/members');
});
function quick(state) {
  filter.follow_state = state;
  load();
}
async function open(row) {
  phone.value = '';
  transcript.value = null;
  try {
    lead.value = await clientRequest('/api/v1/client/leads/' + row.id);
    Object.assign(follow, {
      follow_state: lead.value.follow_state === 'pending' ? 'contacted' : lead.value.follow_state,
      invalid_reason: lead.value.invalid_reason || '',
      note: lead.value.follow_note || '',
      next_follow_at: lead.value.next_follow_at ? lead.value.next_follow_at.slice(0, 16) : '',
    });
    row.unread = false;
  } catch (e) {
    ElMessage.error(e.message);
  }
}
async function reveal() {
  try {
    phone.value = (
      await clientRequest(`/api/v1/client/leads/${lead.value.id}/phone`, 'POST')
    ).phone;
  } catch (e) {
    ElMessage.error(e.message);
  }
}
async function play() {
  try {
    const { url } = await clientRequest(`/api/v1/client/leads/${lead.value.id}/recording`);
    window.open(url, '_blank', 'noopener');
  } catch (e) {
    ElMessage.error(e.message);
  }
}
async function loadTranscript() {
  try {
    transcript.value = await clientRequest(`/api/v1/client/leads/${lead.value.id}/transcript`);
  } catch (e) {
    ElMessage.error(e.message);
  }
}
async function saveFollow() {
  try {
    lead.value = await clientRequest(`/api/v1/client/leads/${lead.value.id}/follow`, 'PUT', {
      ...follow,
      invalid_reason: follow.follow_state === 'invalid' ? follow.invalid_reason : null,
      next_follow_at: follow.next_follow_at ? new Date(follow.next_follow_at).toISOString() : null,
      revision: lead.value.revision,
    });
    ElMessage.success('跟进已保存');
    load();
  } catch (e) {
    ElMessage.error(e.message);
  }
}
async function dispute() {
  try {
    const { value } = await ElMessageBox.prompt(
      '请说明该线索无效的具体情况，平台审核通过后不计入有效交付',
      '提交申诉',
      { inputType: 'textarea', inputValidator: (v) => !!v?.trim() || '请填写申诉说明' },
    );
    lead.value = await clientRequest(`/api/v1/client/leads/${lead.value.id}/dispute`, 'POST', {
      note: value,
    });
    ElMessage.success('申诉已提交');
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message);
  }
}
async function assign(assigneeId) {
  try {
    const r = await clientRequest('/api/v1/client/leads/assign', 'POST', {
      lead_ids: selected.value.map((row) => row.id),
      assignee_id: assigneeId || null,
    });
    ElMessage.success(`已更新 ${r.updated} 条线索`);
    load();
  } catch (e) {
    ElMessage.error(e.message);
  }
}
async function exportCsv() {
  try {
    await clientDownload('/api/v1/client/leads/export?' + params(), 'leads.csv');
  } catch (e) {
    ElMessage.error(e.message);
  }
}
const cards = computed(() => [
  ['未读', 'unread', ''],
  ['待联系', 'pending', 'pending'],
  ['已联系', 'contacted', 'contacted'],
  ['有效', 'valid', 'valid'],
  ['已成交', 'won', 'won'],
  ['无效', 'invalid', 'invalid'],
]);
const canDispute = computed(
  () =>
    lead.value &&
    lead.value.status === 'delivered' &&
    lead.value.follow_state === 'invalid' &&
    lead.value.dispute_state === 'none',
);
</script>
<template>
  <div class="client-leads">
    <div class="metric-row">
      <el-card
        v-for="[label, key, state] in cards"
        :key="key"
        shadow="hover"
        class="metric-card"
        @click="quick(state)"
        ><div class="muted">{{ label }}</div>
        <strong>{{ summary[key] ?? 0 }}</strong></el-card
      >
    </div>
    <el-card>
      <div class="actions">
        <el-input v-model="filter.from" type="date" style="width: 150px" aria-label="开始日期" />
        <span>至</span>
        <el-input v-model="filter.to" type="date" style="width: 150px" aria-label="结束日期" />
        <el-select
          v-model="filter.follow_state"
          clearable
          placeholder="跟进状态"
          style="width: 120px"
          ><el-option v-for="(l, k) in followLabels" :key="k" :label="l" :value="k"
        /></el-select>
        <el-select v-model="filter.status" placeholder="交付状态" style="width: 120px"
          ><el-option label="已交付" value="delivered" /><el-option
            label="已撤回"
            value="withdrawn"
        /></el-select>
        <el-select
          v-if="abilities.assign"
          v-model="filter.assignee"
          clearable
          placeholder="分配情况"
          style="width: 120px"
          ><el-option label="未分配" value="none"
        /></el-select>
        <el-input
          v-model="filter.keyword"
          clearable
          placeholder="姓名或号码后四位"
          style="width: 180px"
          @keyup.enter="load()"
        />
        <el-button type="primary" @click="load()">查询</el-button>
        <el-button v-if="abilities.export" @click="exportCsv">导出</el-button>
      </div>
      <div v-if="abilities.assign && selected.length" class="actions">
        <span>已选 {{ selected.length }} 条，分配给</span>
        <el-select placeholder="选择成员" style="width: 180px" @change="assign"
          ><el-option label="取消分配" :value="0" /><el-option
            v-for="m in members.filter((m) => m.enabled)"
            :key="m.id"
            :label="m.full_name"
            :value="m.id"
        /></el-select>
      </div>
      <el-table
        v-loading="loading"
        :data="page.items"
        row-key="id"
        stripe
        :row-class-name="({ row }) => (row.unread ? 'lead-unread' : '')"
        @selection-change="selected = $event"
        @row-click="open"
        ><el-table-column v-if="abilities.assign" type="selection" width="44" />
        <el-table-column label="交付时间" width="160"
          ><template #default="{ row }">{{
            formatDate(row.delivered_at)
          }}</template></el-table-column
        >
        <el-table-column prop="campaign_name" label="来源任务" min-width="120" />
        <el-table-column prop="contact_name" label="客户" width="110" />
        <el-table-column prop="phone" label="号码" width="130" />
        <el-table-column label="结果" width="100"
          ><template #default="{ row }">{{
            resultLabels[row.result_code] || row.result_code
          }}</template></el-table-column
        >
        <el-table-column label="摘要" min-width="220" show-overflow-tooltip
          ><template #default="{ row }">{{ row.summary || '-' }}</template></el-table-column
        >
        <el-table-column label="跟进" width="90"
          ><template #default="{ row }"
            ><el-tag v-if="row.status === 'withdrawn'" type="info">已撤回</el-tag
            ><span v-else>{{ followLabels[row.follow_state] }}</span></template
          ></el-table-column
        >
        <el-table-column v-if="abilities.assign" prop="assignee_name" label="负责人" width="100" />
      </el-table>
      <div class="actions">
        <span class="muted">共 {{ page.total }} 条</span>
        <el-button v-if="page.next_cursor" link @click="load(page.next_cursor)">加载更多</el-button>
      </div>
    </el-card>
    <el-drawer :model-value="!!lead" size="520px" title="线索详情" @close="lead = null">
      <template v-if="lead">
        <el-alert
          v-if="lead.status === 'withdrawn'"
          type="info"
          :closable="false"
          :title="
            '该线索已撤回：' +
            (withdrawLabels[lead.withdraw_reason] || '') +
            ' ' +
            (lead.withdraw_note || '')
          "
        />
        <el-alert
          v-if="lead.consent_revoked"
          type="warning"
          :closable="false"
          title="客户已撤回授权，号码与录音不可查看"
        />
        <el-descriptions :column="1" border>
          <el-descriptions-item label="客户">{{ lead.contact_name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="号码"
            >{{ phone || lead.phone }}
            <el-button
              v-if="
                lead.abilities.phone_reveal &&
                !phone &&
                !lead.consent_revoked &&
                lead.status === 'delivered'
              "
              link
              type="primary"
              @click="reveal"
              >查看完整号码</el-button
            ></el-descriptions-item
          >
          <el-descriptions-item label="来源任务">{{ lead.campaign_name }}</el-descriptions-item>
          <el-descriptions-item label="结果">{{
            resultLabels[lead.result_code] || lead.result_code
          }}</el-descriptions-item>
          <el-descriptions-item label="交付时间">{{
            formatDate(lead.delivered_at)
          }}</el-descriptions-item>
          <el-descriptions-item v-if="lead.summary !== undefined" label="AI 摘要">{{
            lead.summary
          }}</el-descriptions-item>
          <el-descriptions-item
            v-for="(value, key) in lead.slots || {}"
            :key="key"
            :label="String(key)"
            >{{ value }}</el-descriptions-item
          >
          <el-descriptions-item label="申诉" v-if="lead.dispute_state !== 'none'"
            >{{ disputeLabels[lead.dispute_state] }} {{ lead.dispute_note }}</el-descriptions-item
          >
        </el-descriptions>
        <div class="actions">
          <el-button v-if="lead.abilities.recording" @click="play">播放录音</el-button>
          <el-button v-if="lead.abilities.transcript" @click="loadTranscript"
            >查看通话转写</el-button
          >
        </div>
        <div v-if="transcript" class="transcript">
          <p v-for="(turn, i) in transcript" :key="i">
            <b>{{ turn.speaker_role === 'customer' ? '客户' : 'AI' }}：</b>{{ turn.transcript }}
          </p>
          <p v-if="!transcript.length" class="muted">暂无转写</p>
        </div>
        <template v-if="lead.status === 'delivered'">
          <h4>跟进反馈</h4>
          <el-form label-width="90px">
            <el-form-item label="跟进状态"
              ><el-radio-group v-model="follow.follow_state"
                ><el-radio-button v-for="(l, k) in followLabels" :key="k" :value="k">{{
                  l
                }}</el-radio-button></el-radio-group
              ></el-form-item
            >
            <el-form-item v-if="follow.follow_state === 'invalid'" label="无效原因"
              ><el-select v-model="follow.invalid_reason" placeholder="请选择"
                ><el-option v-for="(l, k) in reasonLabels" :key="k" :label="l" :value="k"
              /></el-select>
            </el-form-item>
            <el-form-item label="下次跟进"
              ><el-input v-model="follow.next_follow_at" type="datetime-local"
            /></el-form-item>
            <el-form-item label="备注"
              ><el-input v-model="follow.note" type="textarea" :rows="3" maxlength="1000"
            /></el-form-item>
            <el-form-item
              ><el-button type="primary" @click="saveFollow">保存跟进</el-button
              ><el-button v-if="canDispute" type="warning" @click="dispute"
                >申诉（{{ lead.abilities.dispute_days }} 天内）</el-button
              ></el-form-item
            >
          </el-form>
        </template>
        <h4>记录</h4>
        <el-timeline
          ><el-timeline-item
            v-for="(e, i) in lead.events"
            :key="i"
            :timestamp="formatDate(e.created_at)"
            >{{
              {
                delivered: '平台交付',
                redelivered: '重新交付',
                withdrawn: '平台撤回',
                follow: '跟进：' + (followLabels[e.follow_state] || ''),
                assigned: '分配',
                dispute_submitted: '提交申诉',
                dispute_accepted: '申诉成立',
                dispute_rejected: '申诉驳回',
              }[e.action] || e.action
            }}
            {{ e.note || '' }}</el-timeline-item
          ></el-timeline
        >
      </template>
    </el-drawer>
  </div>
</template>
