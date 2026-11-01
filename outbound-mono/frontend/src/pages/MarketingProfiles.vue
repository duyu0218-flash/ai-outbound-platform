<script setup>
import { ref } from 'vue';
import { request } from '../session';
import { useData, action } from '../data';
import { formatDate } from '../api';

const overview = useData('/api/v1/marketing/profiles/overview');
const phone = ref(''),
  busy = ref(false),
  result = ref(null);
const codeText = {
  SMS_SUBMITTED: '短信已提交',
  SMS_SUBMIT_FAIL: '短信提交失败',
  SMS_DELIVERED: '短信送达',
  SMS_FAIL_INVALID: '短信失败：空号',
  SMS_FAIL_BLOCKED: '短信失败：拦截',
  SMS_FAIL_TEMP: '短信失败：临时',
  SMS_UNKNOWN: '短信状态未知',
  SMS_REPLY_TD: '回复退订',
  LINK_CLICKED: '点击短链',
  LINK_CONVERTED: '落地页转化',
  CALL_NO_ANSWER: '外呼未接',
  CALL_BUSY: '外呼占线',
  CALL_REJECTED: '外呼拒接',
  CALL_INVALID: '外呼空号',
  CALL_POWER_OFF: '关机',
  CALL_SUSPENDED: '停机',
  CALL_LINE_FAIL: '线路失败',
  CALL_ANSWERED: '外呼接通',
  ANS_INSTANT_HANGUP: '秒挂',
  ANS_SHORT_HANGUP: '短时挂断',
  ANS_VOICE_ASSISTANT: '语音助手',
  ANS_VOICEMAIL: '语音信箱',
  ANS_NOT_OWNER: '非本人',
  ANS_REFUSE_HARD: '强烈拒绝',
  ANS_CONVERSATION: '有效对话',
  INTENT_A: '意向 A',
  INTENT_B: '意向 B',
  INTENT_C: '意向 C',
  INTENT_D: '意向 D（拒绝）',
  INTENT_E: '意向 E',
  CONVERTED: '已转化',
};
const channelText = { sms: '短信', call: '外呼', link: '短链' };
const stageType = {
  已转化: 'success',
  商机: 'success',
  活跃: 'primary',
  高潜: 'warning',
  沉默: 'info',
  不可触达: 'danger',
};
async function lookup() {
  if (!phone.value.trim()) return;
  busy.value = true;
  try {
    // The number travels only in the POST body; the server turns it into a hash.
    result.value = await action(
      () => request('/api/v1/marketing/profiles/lookup', 'POST', { phone: phone.value.trim() }),
      { success: '' },
    );
  } finally {
    busy.value = false;
  }
}
const yes = (v) => (v ? '是' : '否');
</script>
<template>
  <PageHeader
    title="用户触达档案"
    description="按号码查看用户的全部触达记录、计数与生命周期阶段。号码只用于本次查询，不会保存明文。"
  />
  <el-card>
    <div class="actions">
      <el-input
        v-model="phone"
        placeholder="输入手机号，例如 13800138000"
        style="width: 280px"
        clearable
        @keyup.enter="lookup"
      /><el-button type="primary" :loading="busy" @click="lookup">查询档案</el-button>
    </div>
  </el-card>

  <template v-if="result">
    <el-card style="margin-top: 12px">
      <template #header
        ><div class="actions">
          <strong>{{ result.mask }}</strong>
          <el-tag v-if="result.profile" :type="stageType[result.profile.stage]">{{
            result.profile.stage
          }}</el-tag>
          <span v-else class="hint">暂无触达记录（阶段：待挖掘）</span>
        </div></template
      >
      <el-descriptions v-if="result.profile" :column="4" border size="small">
        <el-descriptions-item label="短信发送">{{ result.profile.sms_count }}</el-descriptions-item>
        <el-descriptions-item label="短信送达">{{
          result.profile.sms_delivered
        }}</el-descriptions-item>
        <el-descriptions-item label="短链点击">{{
          result.profile.link_clicks
        }}</el-descriptions-item>
        <el-descriptions-item label="已转化">{{
          yes(result.profile.converted)
        }}</el-descriptions-item>
        <el-descriptions-item label="外呼次数">{{
          result.profile.call_count
        }}</el-descriptions-item>
        <el-descriptions-item label="接通">{{
          result.profile.call_connected
        }}</el-descriptions-item>
        <el-descriptions-item label="秒挂">{{
          result.profile.instant_hangups
        }}</el-descriptions-item>
        <el-descriptions-item label="有效对话">{{
          result.profile.conversations
        }}</el-descriptions-item>
        <el-descriptions-item label="语音助手">{{
          yes(result.profile.voice_assistant)
        }}</el-descriptions-item>
        <el-descriptions-item label="最高意向">{{
          codeText[result.profile.best_intent] || '-'
        }}</el-descriptions-item>
        <el-descriptions-item label="最近触达">{{
          formatDate(result.profile.last_touch_at)
        }}</el-descriptions-item>
        <el-descriptions-item label="最近渠道">{{
          channelText[result.profile.last_channel] || '-'
        }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
    <el-row :gutter="12" style="margin-top: 12px">
      <el-col :span="14">
        <el-card header="触达时间线">
          <el-empty v-if="!result.timeline.length" description="暂无触达记录" />
          <el-timeline v-else>
            <el-timeline-item
              v-for="t in result.timeline"
              :key="t.id"
              :timestamp="formatDate(t.occurred_at)"
              >{{ channelText[t.channel] || t.channel }} ·
              {{ codeText[t.code] || t.code }}</el-timeline-item
            >
          </el-timeline>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card header="阶段变化">
          <DataGrid
            :rows="result.history"
            :columns="[
              { key: 'created_at', label: '时间', date: true },
              { key: 'from_stage', label: '原阶段' },
              { key: 'to_stage', label: '新阶段' },
              { key: 'code', label: '触发结果', render: (v) => codeText[v] || v || '-' },
            ]"
          />
        </el-card>
        <el-card header="参与的营销活动" style="margin-top: 12px">
          <DataGrid
            :rows="result.journeys"
            :columns="[
              { key: 'name', label: '活动' },
              { key: 'grp', label: '分组', render: (v) => (v === 'control' ? '对照组' : '实验组') },
              { key: 'node_id', label: '当前节点' },
              { key: 'created_at', label: '加入时间', date: true },
            ]"
          />
        </el-card>
      </el-col>
    </el-row>
  </template>

  <el-card header="阶段分布" style="margin-top: 12px">
    <el-alert v-if="overview.error.value" :title="overview.error.value" type="error" />
    <div class="metrics">
      <el-card v-for="(n, stage) in overview.data.value?.stages || {}" :key="stage" shadow="never"
        ><span class="hint">{{ stage }}</span>
        <div class="metric-value">{{ n }}</div></el-card
      >
    </div>
  </el-card>
</template>
