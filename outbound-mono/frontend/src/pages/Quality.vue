<script setup>
import { ref, computed } from 'vue';
import { useData } from '../data';
import { t } from '../i18n';
import AnalysisReview from '../components/AnalysisReview.vue';
import MetricCards from '../components/MetricCards.vue';
const sorting = ref(null),
  state = ref('auto'),
  score = ref(),
  selected = ref(null),
  review = ref(false),
  page = ref(1),
  queue = useData(() => {
    const params = new URLSearchParams({ page: '1', size: '200' });
    if (state.value !== 'all') params.set('review_state', state.value);
    if (score.value != null && score.value !== '') params.set('max_score', score.value);
    return '/api/v1/quality/reviews?' + params;
  }),
  speech = useData(() =>
    selected.value ? `/api/v1/calls/${selected.value.call_id}/speech-turns?final_only=true` : '',
  ),
  recordings = useData(() =>
    selected.value ? `/api/v1/calls/${selected.value.call_id}/recordings` : '',
  );
const sortedRows = computed(() => {
  const rows = [...(queue.data.value || [])];
  if (sorting.value?.order)
    rows.sort((a, b) => (sorting.value.order === 'ascending' ? 1 : -1) * (a.qa_score - b.qa_score));
  return rows;
});
const stats = computed(() => {
  const rows = queue.data.value || [];
  return {
    pending: rows.filter((v) => v.review_state === 'auto').length,
    flagged: rows.filter((v) => {
      try {
        return JSON.parse(v.qa_flags_json).length > 0;
      } catch {
        return !!v.qa_flags_json;
      }
    }).length,
    average: rows.length ? Math.round(rows.reduce((a, b) => a + b.qa_score, 0) / rows.length) : 0,
  };
});
</script>
<template>
  <PageHeader title="qualityReview" description="qualityReviewHint"
    ><el-button @click="queue.load">{{ t('refresh') }}</el-button></PageHeader
  ><MetricCards
    :data="stats"
    :items="[
      { key: 'pending', label: 'pendingReview' },
      { key: 'flagged', label: 'flaggedCalls' },
      { key: 'average', label: 'averageQaScore', suffix: '/ 100' },
    ]"
  /><el-card
    ><div class="toolbar">
      <el-select v-model="state"
        ><el-option
          v-for="[value, label] in [
            ['auto', 'pendingReview'],
            ['reviewed', 'reviewed'],
            ['all', 'all'],
          ]"
          :key="value"
          :value="value"
          :label="t(label)" /></el-select
      ><el-select v-model="score" clearable :placeholder="t('scoreThreshold')"
        ><el-option
          v-for="value in [60, 70, 80, 90]"
          :key="value"
          :value="value"
          :label="t('scoreAtMost', { count: value })"
      /></el-select>
    </div>
    <el-alert v-if="queue.error.value" :title="queue.error.value" type="error" /><DataGrid
      :rows="sortedRows.slice((page - 1) * 20, page * 20)"
      @sort="sorting = $event"
      row-key="call_id"
      :loading="queue.loading.value"
      :columns="[
        { key: 'phone', label: 'phone' },
        {
          key: 'campaign_name',
          label: 'campaigns',
          render: (v, r) => v || (r.campaign_id ? '#' + r.campaign_id : '-'),
        },
        { key: 'call_status', label: 'status', translate: true, tag: true },
        { key: 'result_code', label: 'callResult' },
        { key: 'intent', label: 'intent' },
        { key: 'qa_score', label: 'qaScore', sortable: 'custom' },
        { key: 'review_state', label: 'reviewState', translate: true },
        { key: 'updated_at', label: 'lastUpdated', date: true },
      ]"
      ><template #actions="{ row }"
        ><el-button
          size="small"
          @click="
            selected = row;
            review = false;
          "
          >{{ t('evidence') }}</el-button
        ><el-button
          size="small"
          type="primary"
          @click="
            selected = row;
            review = true;
          "
          >{{ t(row.review_state === 'reviewed' ? 'reReview' : 'review') }}</el-button
        ></template
      ></DataGrid
    ><el-pagination
      v-model:current-page="page"
      :total="queue.data.value?.length || 0"
      :page-size="20"
      layout="prev,pager,next" /></el-card
  ><el-drawer
    :model-value="!!selected && !review"
    :title="t('qualityEvidence') + ' · ' + (selected?.phone || '')"
    size="720px"
    @close="selected = null"
    ><template v-if="selected"
      ><el-button type="primary" @click="review = true">{{ t('review') }}</el-button
      ><el-descriptions :column="1" border
        ><el-descriptions-item
          v-for="[key, label] in [
            ['result_code', 'callResult'],
            ['intent', 'intent'],
            ['sentiment', 'sentiment'],
            ['qa_score', 'qaScore'],
            ['qa_flags_json', 'qaFlags'],
            ['summary', 'summary'],
          ]"
          :key="key"
          :label="t(label)"
          >{{ selected[key] || '-' }}</el-descriptions-item
        ></el-descriptions
      ><el-tabs
        ><el-tab-pane :label="t('transcriptEvidence')"
          ><div
            v-for="item in speech.data.value || []"
            :key="item.id"
            class="speech-turn"
            :class="item.speaker_role"
          >
            <el-tag>{{ t(item.speaker_role) }}</el-tag>
            <p>{{ item.transcript || '-' }}</p>
          </div>
          <el-empty v-if="!speech.data.value?.length" :description="t('empty')" /></el-tab-pane
        ><el-tab-pane :label="t('recordingEvidence')"
          ><div v-for="item in recordings.data.value || []" :key="item.id" class="record-card">
            <el-tag>{{ item.state }}</el-tag>
            {{ item.attempt ? '第 ' + item.attempt + ' 轮' : '历史轮次未知' }}
            {{ item.media_format || 'unknown' }}
            <p>{{ item.storage_uri || item.provider_url || '-' }}</p>
          </div>
          <el-empty
            v-if="!recordings.data.value?.length"
            :description="t('empty')" /></el-tab-pane></el-tabs></template></el-drawer
  ><AnalysisReview
    v-if="review && selected"
    :analysis="selected"
    :call-id="selected.call_id"
    @close="review = false"
    @saved="
      selected = null;
      queue.load();
    "
  />
</template>
