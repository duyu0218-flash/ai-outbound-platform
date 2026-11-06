<script setup>
import { ref, computed } from 'vue';
import { session } from '../session';
import { useData } from '../data';
import { t } from '../i18n';
import { formatDate } from '../api';
import RecordingPlayer from './RecordingPlayer.vue';
import AnalysisReview from './AnalysisReview.vue';
const props = defineProps({ call: Object, role: { default: 'admin' } }),
  emit = defineEmits(['close']),
  review = ref(false),
  nodeOpen = ref(null),
  base = () => '/api/v1/calls/' + props.call.id;
const permits = useData(() => (props.role === 'admin' ? null : '/api/v1/ops/me')),
  canRecord = computed(() => props.role === 'admin' || !!permits.data.value?.can_record);
const frozenFlow = useData(() => (nodeOpen.value ? base() + '/flow' : ''));
const voiceAi = useData(() => base() + '/voice-ai'),
  speech = useData(() => base() + '/speech-turns'),
  analysis = useData(() => base() + '/analysis'),
  recordings = useData(() => (canRecord.value ? base() + '/recordings' : null)),
  metrics = useData(() => base() + '/metrics'),
  events = useData(() => base() + '/events?page=1&size=100');
</script>
<template>
  <el-drawer
    :model-value="true"
    :title="t('events') + ' · ' + call.phone"
    size="720px"
    @close="emit('close')"
    ><template
      v-for="(q, index) in [speech, analysis, recordings, metrics, events, voiceAi]"
      :key="index"
      ><el-alert v-if="q.error.value" type="error" :title="q.error.value" /></template
    ><el-alert
      v-if="permits.data.value && !canRecord"
      title="当前账号没有录音访问权限"
      type="info" /><el-descriptions :column="1" border
      ><el-descriptions-item label="ID">{{ call.id }}</el-descriptions-item
      ><el-descriptions-item
        v-for="[key, label] in [
          ['campaign_name', '任务名称'],
          ['created_at', '拨打时间'],
          ['phone', '被叫号码'],
          ['city', '归属城市'],
          ['script_name', '呼叫模板'],
          ['line_name', '线路名称'],
          ['telephony_seconds', '通话时长（秒）'],
          ['grade', '意向评级'],
          ['stage', '跟进状态'],
          ['review_state', '质检状态'],
        ]"
        :key="key"
        :label="label"
        >{{
          key === 'created_at' ? formatDate(call[key]) : (call[key] ?? '—')
        }}</el-descriptions-item
      ><el-descriptions-item :label="t('status')">{{ t(call.status) }}</el-descriptions-item
      ><el-descriptions-item :label="t('mode')">{{
        t(call.mode)
      }}</el-descriptions-item></el-descriptions
    ><el-descriptions
      v-for="attempt in voiceAi.data.value || []"
      :key="attempt.attempt"
      :column="1"
      border
    >
      <el-descriptions-item :label="'第 ' + attempt.attempt + ' 次通话方案'"
        >{{ attempt.name }} ·
        {{
          attempt.route === 'realtime'
            ? '端到端实时语音'
            : attempt.route === 'cascade'
              ? '三段式'
              : '原有链路'
        }}</el-descriptions-item
      >
      <el-descriptions-item label="话术校验值">{{
        attempt.script_sha
      }}</el-descriptions-item> </el-descriptions
    ><el-tabs
      ><el-tab-pane label="结构化转写"
        ><div class="speech-list" v-loading="speech.loading.value">
          <div
            v-for="item in speech.data.value || []"
            :key="item.id"
            class="speech-turn"
            :class="item.speaker_role"
          >
            <el-tag>{{ item.speaker_role }} · {{ item.is_final ? '最终' : '临时' }}</el-tag
            ><span class="hint">
              置信度
              {{ item.confidence == null ? '-' : Math.round(item.confidence * 100) + '%' }}</span
            >
            <span>{{
              item.start_ms == null
                ? formatDate(item.created_at)
                : (item.start_ms / 1000).toFixed(2) + ' 秒'
            }}</span
            ><el-button v-if="item.node_id" link @click="nodeOpen = item.node_id"
              >节点：{{ item.node_label || item.node_id }}</el-button
            >
            <p>{{ item.transcript || '（空转写）' }}</p>
          </div>
          <el-tag
            v-for="event in (events.data.value || []).filter(
              (e) =>
                ['call_completed', 'hangup', 'status_completed'].includes(e.event_type) ||
                JSON.stringify(e.payload).includes('hangup_reason'),
            )"
            :key="event.id"
            >{{ formatDate(event.created_at) }} 挂机事件：{{
              event.payload?.hangup_reason || event.event_type
            }}</el-tag
          >
          <el-empty
            v-if="!speech.data.value?.length"
            :description="t('empty')"
          /></div></el-tab-pane
      ><el-tab-pane :label="t('resultsAndQa')"
        ><template v-if="analysis.data.value"
          ><el-descriptions :column="1" border
            ><el-descriptions-item
              v-for="[key, label] in [
                ['result_code', 'callResult'],
                ['intent', 'intent'],
                ['sentiment', 'sentiment'],
                ['qa_score', 'qaScore'],
                ['qa_flags_json', 'qaFlags'],
                ['review_state', 'reviewState'],
                ['summary', 'summary'],
              ]"
              :key="key"
              :label="t(label)"
              >{{ analysis.data.value[key] }}</el-descriptions-item
            ></el-descriptions
          ><el-tag v-if="analysis.data.value.needs_review" type="warning">有新证据，待复核</el-tag
          ><el-button
            v-if="role === 'admin' || session.user?.is_supervisor"
            type="primary"
            @click="review = true"
            >{{ t('correctAnalysis') }}</el-button
          ></template
        ><el-empty
          v-else
          :description="analysis.loading.value ? t('loading') : t('empty')" /></el-tab-pane
      ><el-tab-pane v-if="canRecord" label="录音资产"
        ><div v-for="item in recordings.data.value || []" :key="item.id" class="record-card">
          <div class="actions">
            <el-tag>{{ item.state }}</el-tag
            ><el-tag>{{ item.attempt ? '第 ' + item.attempt + ' 轮' : '历史轮次未知' }}</el-tag
            ><span
              >{{ item.media_format || 'unknown' }} ·
              {{ item.duration_sec == null ? '-' : item.duration_sec + 's' }}</span
            >
          </div>
          <p>{{ item.storage_uri || item.provider_url }}</p>
          <RecordingPlayer :call-id="call.id" :asset="item" />
        </div>
        <el-empty v-if="!recordings.data.value?.length" :description="t('empty')" /></el-tab-pane
      ><el-tab-pane label="阶段指标"
        ><DataGrid
          :rows="metrics.data.value"
          :columns="[
            { key: 'stage', label: '阶段' },
            { key: 'duration_ms', label: '耗时', render: (v) => (v == null ? '-' : v + ' ms') },
            { key: 'success', label: '结果' },
            { key: 'error_code', label: '错误码' },
          ]" /></el-tab-pane
      ><el-tab-pane :label="t('events')"
        ><div v-for="event in events.data.value || []" :key="event.id" class="record-card">
          <el-tag>{{ event.event_type }}</el-tag> {{ formatDate(event.created_at)
          }}<JsonView :value="event.payload" />
        </div>
        <el-empty
          v-if="!events.data.value?.length"
          :description="t('empty')" /></el-tab-pane></el-tabs></el-drawer
  ><el-dialog
    :model-value="!!nodeOpen"
    title="通话时冻结的话术流程"
    width="760px"
    @close="nodeOpen = null"
    ><el-alert
      v-if="frozenFlow.error.value"
      :title="frozenFlow.error.value"
      type="error" /><el-table :data="frozenFlow.data.value?.nodes || []"
      ><el-table-column label="当前节点"
        ><template #default="{ row }"
          ><el-tag :type="row.id === nodeOpen ? 'success' : 'info'">{{
            row.label
          }}</el-tag></template
        ></el-table-column
      ><el-table-column prop="type" label="类型" /><el-table-column
        prop="text"
        label="话术内容" /></el-table
    ><el-table
      :data="
        (frozenFlow.data.value?.edges || []).filter(
          (e) => e.source === nodeOpen || e.target === nodeOpen,
        )
      "
      ><el-table-column prop="source" label="起点" /><el-table-column
        prop="condition"
        label="分支条件" /><el-table-column prop="target" label="终点" /></el-table></el-dialog
  ><AnalysisReview
    v-if="review && analysis.data.value"
    :analysis="analysis.data.value"
    :call-id="call.id"
    @close="review = false"
    @saved="analysis.load"
  />
</template>
