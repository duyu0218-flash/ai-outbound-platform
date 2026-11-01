<script setup>
import { ref, computed } from 'vue';
import { request, download } from '../session';
import { useData, action } from '../data';
import { ElMessage } from 'element-plus';
const tab = ref('datasets');
const datasets = useData('/api/v1/marketing/datasets'),
  partners = useData('/api/v1/marketing/partners'),
  adapters = useData('/api/v1/marketing/match/adapters'),
  jobs = useData('/api/v1/marketing/match-jobs', 10000),
  ready = useData('/api/v1/marketing/dataset-versions');
const states = {
  importing: '导入中',
  ready: '已就绪',
  failed: '失败',
  queued: '排队中',
  running: '撞库中',
  paused: '已暂停',
  awaiting_file: '待交换文件',
  finished: '已完成',
};
const rowStates = { valid: '有效', invalid: '无效', duplicate: '重复' };
const adapterName = (id) => adapters.data.value?.find((a) => a.id === id)?.label || id;

// data packages and versions
const creating = ref(false),
  form = ref({ name: '', description: '' }),
  current = ref(null),
  versions = useData(() =>
    current.value ? `/api/v1/marketing/datasets/${current.value.id}/versions` : '',
  ),
  uploading = ref(false),
  uploadForm = ref({ authorizationRef: '', source: '' }),
  fileInput = ref(),
  busy = ref(false);
async function createDataset() {
  const created = await action(() => request('/api/v1/marketing/datasets', 'POST', form.value), {
    refresh: datasets.load,
  });
  creating.value = false;
  current.value = created;
}
async function upload() {
  const file = fileInput.value?.files?.[0];
  if (!file) {
    ElMessage.warning('请选择 CSV 或 TXT 文件');
    return;
  }
  const data = new FormData();
  data.append('file', file);
  if (uploadForm.value.authorizationRef)
    data.append('authorizationRef', uploadForm.value.authorizationRef);
  if (uploadForm.value.source) data.append('source', uploadForm.value.source);
  busy.value = true;
  try {
    await action(
      () =>
        request(`/api/v1/marketing/datasets/${current.value.id}/versions`, 'POST', data, {
          timeoutMs: 600000,
        }),
      { refresh: versions.load, success: '已上传，导入结果见版本列表' },
    );
    uploading.value = false;
    datasets.load();
    ready.load();
  } finally {
    busy.value = false;
  }
}

// version detail
const version = ref(null),
  rowFilter = ref(''),
  rowPage = ref(1),
  rows = useData(() =>
    version.value
      ? `/api/v1/marketing/dataset-versions/${version.value.id}/rows?page=${rowPage.value}&size=50&status=${rowFilter.value}`
      : '',
  ),
  authRef = ref(''),
  matchForm = ref({ partnerId: '', topN: 3 });
function openVersion(row) {
  version.value = row;
  authRef.value = row.authorization_ref || '';
  rowFilter.value = '';
  rowPage.value = 1;
}
async function refreshVersion() {
  version.value = await request(`/api/v1/marketing/dataset-versions/${version.value.id}`);
  rows.load();
  versions.load();
}
async function processVersion() {
  version.value = await action(() =>
    request(`/api/v1/marketing/dataset-versions/${version.value.id}/process`, 'POST'),
  );
  rows.load();
  versions.load();
}
async function saveAuth() {
  version.value = await action(() =>
    request(`/api/v1/marketing/dataset-versions/${version.value.id}/authorization`, 'PUT', {
      authorizationRef: authRef.value,
    }),
  );
  versions.load();
  ready.load();
}
async function submitMatch() {
  if (!matchForm.value.partnerId) throw new Error('请选择合作方');
  await action(
    () =>
      request('/api/v1/marketing/match-jobs', 'POST', {
        ...matchForm.value,
        versionId: version.value.id,
      }),
    { refresh: jobs.load, success: '撞库任务已提交' },
  );
  version.value = null;
  tab.value = 'jobs';
}

// partners
const partnerOpen = ref(false),
  partnerForm = ref({});
function newPartner() {
  partnerForm.value = { code: '', name: '', adapter: 'mock', product: '' };
  partnerOpen.value = true;
}
async function savePartner() {
  await action(() => request('/api/v1/marketing/partners', 'POST', partnerForm.value), {
    refresh: partners.load,
  });
  partnerOpen.value = false;
}

// match jobs
const analysis = ref(null),
  hitInput = ref(),
  hitJob = ref(null),
  antOpen = ref(false),
  antForm = ref({ partnerId: '', versionId: '' }),
  antInput = ref();
async function runJob(row) {
  await action(() => request(`/api/v1/marketing/match-jobs/${row.id}/run`, 'POST'), {
    refresh: jobs.load,
  });
}
async function resumeJob(row) {
  await action(() => request(`/api/v1/marketing/match-jobs/${row.id}/resume`, 'POST'), {
    confirm: '已发出但结果未确认的请求不会重发，确认继续撞库？',
    refresh: jobs.load,
  });
}
async function openAnalysis(row) {
  analysis.value = await action(() => request(`/api/v1/marketing/match-jobs/${row.id}/analysis`), {
    success: '',
  });
}
async function exportHashes(row) {
  await action(
    () => download(`/api/v1/marketing/match-jobs/${row.id}/hash-file`, `match-${row.id}.txt`),
    { refresh: jobs.load, success: '已导出' },
  );
}
function pickHits(row) {
  hitJob.value = row;
  hitInput.value.click();
}
async function importHits(event) {
  const file = event.target.files?.[0];
  if (!file) return;
  const data = new FormData();
  data.append('file', file);
  try {
    const r = await action(
      () => request(`/api/v1/marketing/match-jobs/${hitJob.value.id}/hit-file`, 'POST', data),
      { refresh: jobs.load, success: '' },
    );
    action(async () => r, {
      success: `命中 ${r.matched}，未命中 ${r.missed}，无法对应 ${r.unknown}`,
    });
  } finally {
    event.target.value = '';
  }
}
async function importAnt() {
  const file = antInput.value?.files?.[0];
  if (!file || !antForm.value.partnerId) throw new Error('请选择合作方和结果文件');
  const data = new FormData();
  data.append('file', file);
  data.append('partnerId', antForm.value.partnerId);
  if (antForm.value.versionId) data.append('versionId', antForm.value.versionId);
  const r = await action(
    () => request('/api/v1/marketing/match-imports/ant-platform', 'POST', data),
    { refresh: jobs.load, success: '' },
  );
  antOpen.value = false;
  action(async () => r, {
    success: `已导入 ${r.imported} 个号码；跳过未确认 ${r.skipped_rows} 行，无效 ${r.invalid_rows} 行`,
  });
}
const maxHour = computed(() =>
  Math.max(1, ...(analysis.value?.best_hours || []).map((h) => h.count)),
);
const maxBucket = computed(() =>
  Math.max(1, ...(analysis.value?.score_distribution || []).map((b) => b.count)),
);
</script>
<template>
  <PageHeader
    title="数据包与撞库"
    description="导入存量数据包（号码只保存加密值和掩码），提交合作方撞库，查看命中率、分数分布与重合度。"
  />
  <el-tabs v-model="tab">
    <el-tab-pane label="数据包" name="datasets">
      <el-card>
        <div class="actions">
          <el-button
            type="primary"
            @click="
              form = { name: '', description: '' };
              creating = true;
            "
            >新建数据包</el-button
          >
        </div>
        <el-alert v-if="datasets.error.value" :title="datasets.error.value" type="error" />
        <DataGrid
          :rows="datasets.data.value || []"
          :loading="datasets.loading.value"
          :pagination="10"
          :columns="[
            { key: 'name', label: '数据包' },
            { key: 'description', label: '说明' },
            { key: 'version_count', label: '版本数' },
            { key: 'valid_count', label: '有效号码' },
            { key: 'created_at', label: '创建时间', date: true },
          ]"
          ><template #actions="{ row }"
            ><el-button size="small" type="primary" @click="current = row"
              >版本</el-button
            ></template
          ></DataGrid
        >
      </el-card>
      <el-card v-if="current" style="margin-top: 16px">
        <div class="actions">
          <strong>{{ current.name }} · 版本</strong>
          <el-button
            type="primary"
            @click="
              uploadForm = { authorizationRef: '', source: '' };
              uploading = true;
            "
            >上传新版本</el-button
          >
        </div>
        <DataGrid
          :rows="versions.data.value || []"
          :loading="versions.loading.value"
          :columns="[
            { key: 'version_no', label: '版本' },
            { key: 'file_name', label: '文件' },
            { key: 'state', label: '状态', render: (v) => states[v] || v },
            { key: 'total_rows', label: '总行数' },
            { key: 'valid_count', label: '有效' },
            { key: 'duplicate_count', label: '重复' },
            { key: 'invalid_count', label: '无效' },
            { key: 'blocked_count', label: '准入受限' },
            { key: 'authorization_ref', label: '授权凭证', render: (v) => v || '未填写' },
          ]"
          ><template #actions="{ row }"
            ><el-button size="small" type="primary" @click="openVersion(row)"
              >明细与撞库</el-button
            ></template
          ></DataGrid
        >
      </el-card>
    </el-tab-pane>

    <el-tab-pane label="合作方" name="partners">
      <el-card>
        <div class="actions">
          <el-button type="primary" @click="newPartner">新增合作方</el-button>
        </div>
        <el-alert
          v-for="a in (adapters.data.value || []).filter((a) => !a.configured)"
          :key="a.id"
          :title="`${a.label}：${a.reason}`"
          type="warning"
          :closable="false"
          style="margin-bottom: 8px"
        />
        <DataGrid
          :rows="partners.data.value || []"
          :loading="partners.loading.value"
          :columns="[
            { key: 'name', label: '合作方' },
            { key: 'code', label: '编码' },
            { key: 'adapter', label: '撞库方式', render: adapterName },
            { key: 'product', label: '产品' },
            { key: 'enabled', label: '启用' },
          ]"
        />
      </el-card>
    </el-tab-pane>

    <el-tab-pane label="撞库任务" name="jobs">
      <el-card>
        <div class="actions">
          <el-button @click="antOpen = true">导入 ant-platform 结果</el-button
          ><el-button @click="jobs.load()">刷新</el-button>
        </div>
        <input ref="hitInput" type="file" accept=".csv,.txt" hidden @change="importHits" />
        <DataGrid
          :rows="jobs.data.value || []"
          :loading="jobs.loading.value"
          :pagination="20"
          :columns="[
            { key: 'partner_name', label: '合作方' },
            { key: 'adapter', label: '方式', render: adapterName },
            {
              key: 'origin',
              label: '来源',
              render: (v) => (v === 'import' ? '导入结果' : '数据包'),
            },
            { key: 'state', label: '状态', render: (v) => states[v] || v },
            { key: 'total', label: '号码数' },
            {
              key: 'counts',
              label: '进度',
              width: 260,
              render: (c) =>
                `成功 ${c.success} · 无数据 ${c.no_data} · 缓存 ${c.cached} · 失败 ${c.failed} · 待核对 ${c.uncertain + c.pending} · 待发 ${c.reserved}`,
            },
            { key: 'counts.charged', label: '计费次数' },
            { key: 'pause_reason', label: '暂停原因' },
            { key: 'created_at', label: '提交时间', date: true },
          ]"
          ><template #actions="{ row }"
            ><el-button
              v-if="['queued', 'running'].includes(row.state)"
              size="small"
              @click="runJob(row)"
              >立即执行</el-button
            ><el-button v-if="row.state === 'paused'" size="small" @click="resumeJob(row)"
              >继续</el-button
            ><template v-if="row.state === 'awaiting_file'"
              ><el-button size="small" @click="exportHashes(row)">导出 SHA256</el-button
              ><el-button size="small" @click="pickHits(row)">导入命中文件</el-button></template
            ><el-button size="small" type="primary" @click="openAnalysis(row)"
              >分析</el-button
            ></template
          ></DataGrid
        >
      </el-card>
    </el-tab-pane>
  </el-tabs>

  <el-dialog v-model="creating" title="新建数据包" width="520px">
    <el-form label-position="top"
      ><el-form-item label="名称"><el-input v-model="form.name" maxlength="128" /></el-form-item
      ><el-form-item label="说明"
        ><el-input v-model="form.description" type="textarea" maxlength="512" /></el-form-item
    ></el-form>
    <template #footer><el-button type="primary" @click="createDataset">创建</el-button></template>
  </el-dialog>

  <el-dialog v-model="uploading" title="上传数据包版本" width="560px">
    <el-alert
      type="info"
      :closable="false"
      title="支持 CSV/TXT（UTF-8 或 GBK/GB18030）。表头可用 phone、userTel、mobile、手机号、手机号码；地区、运营商、来源单独成列，其余列作为扩展属性。也可每行一个号码。"
    />
    <el-form label-position="top" style="margin-top: 12px"
      ><el-form-item label="文件"
        ><input ref="fileInput" type="file" accept=".csv,.txt" /></el-form-item
      ><el-form-item label="授权凭证编号（提交撞库前必须填写）"
        ><el-input
          v-model="uploadForm.authorizationRef"
          placeholder="如 CONTRACT/2026-11" /></el-form-item
      ><el-form-item label="数据来源（选填）"><el-input v-model="uploadForm.source" /></el-form-item
    ></el-form>
    <template #footer
      ><el-button type="primary" :loading="busy" @click="upload">上传并导入</el-button></template
    >
  </el-dialog>

  <el-drawer
    :model-value="version !== null"
    size="70%"
    title="版本明细"
    destroy-on-close
    @close="version = null"
  >
    <template v-if="version">
      <el-descriptions :column="4" border>
        <el-descriptions-item label="状态">{{ states[version.state] }}</el-descriptions-item>
        <el-descriptions-item label="编码">{{ version.encoding }}</el-descriptions-item>
        <el-descriptions-item label="导入进度"
          >{{ version.chunk_done }} / {{ version.chunk_total }}</el-descriptions-item
        >
        <el-descriptions-item label="总行数">{{ version.total_rows }}</el-descriptions-item>
        <el-descriptions-item label="有效">{{ version.valid_count }}</el-descriptions-item>
        <el-descriptions-item label="重复">{{ version.duplicate_count }}</el-descriptions-item>
        <el-descriptions-item label="无效">{{ version.invalid_count }}</el-descriptions-item>
        <el-descriptions-item label="准入受限">{{ version.blocked_count }}</el-descriptions-item>
      </el-descriptions>
      <div class="actions" style="margin: 12px 0">
        <el-button v-if="version.state === 'importing'" @click="processVersion">继续导入</el-button>
        <el-button @click="refreshVersion">刷新</el-button>
        <el-input v-model="authRef" placeholder="授权凭证编号" style="width: 240px" /><el-button
          @click="saveAuth"
          >保存授权凭证</el-button
        >
      </div>
      <el-card v-if="version.state === 'ready'" shadow="never" style="margin-bottom: 12px">
        <div class="actions">
          <strong>提交撞库</strong>
          <el-select v-model="matchForm.partnerId" placeholder="选择合作方" style="width: 220px"
            ><el-option
              v-for="p in partners.data.value || []"
              :key="p.id"
              :label="`${p.name}（${adapterName(p.adapter)}）`"
              :value="p.id" /></el-select
          ><el-select v-model="matchForm.topN" style="width: 140px"
            ><el-option v-for="n in [1, 2, 3]" :key="n" :label="`推荐 ${n} 个时段`" :value="n"
          /></el-select>
          <el-button type="primary" :disabled="!version.authorization_ref" @click="submitMatch"
            >提交</el-button
          >
          <span v-if="!version.authorization_ref" class="muted">缺少授权凭证，不能提交撞库</span>
        </div>
      </el-card>
      <el-radio-group v-model="rowFilter" @change="rowPage = 1">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="valid">可触达</el-radio-button>
        <el-radio-button value="blocked">准入受限</el-radio-button>
        <el-radio-button value="duplicate">重复</el-radio-button>
        <el-radio-button value="invalid">无效</el-radio-button>
      </el-radio-group>
      <DataGrid
        :rows="rows.data.value?.items || []"
        :loading="rows.loading.value"
        :columns="[
          { key: 'line_no', label: '行号' },
          { key: 'phone_mask', label: '号码' },
          { key: 'status', label: '状态', render: (v) => rowStates[v] || v },
          { key: 'reason', label: '原因' },
          { key: 'blocked_reason', label: '准入结果', render: (v) => v || '可触达' },
          { key: 'region', label: '地区' },
          { key: 'carrier', label: '运营商' },
          { key: 'source', label: '来源' },
          { key: 'attrs', label: '扩展属性' },
        ]"
      />
      <el-pagination
        v-model:current-page="rowPage"
        layout="total, prev, pager, next"
        :page-size="50"
        :total="rows.data.value?.total || 0"
      />
    </template>
  </el-drawer>

  <el-dialog v-model="partnerOpen" title="新增合作方" width="560px">
    <el-form label-position="top"
      ><el-form-item label="名称"><el-input v-model="partnerForm.name" /></el-form-item
      ><el-form-item label="编码（2-64 位字母、数字、_ 或 -）"
        ><el-input v-model="partnerForm.code" /></el-form-item
      ><el-form-item label="撞库方式"
        ><el-select v-model="partnerForm.adapter" style="width: 100%"
          ><el-option
            v-for="a in adapters.data.value || []"
            :key="a.id"
            :label="a.label + (a.configured ? '' : '（未配置）')"
            :value="a.id" /></el-select></el-form-item
      ><el-form-item label="产品编码（选填，结果按合作方 + 产品缓存 30 天）"
        ><el-input v-model="partnerForm.product" /></el-form-item
    ></el-form>
    <template #footer><el-button type="primary" @click="savePartner">保存</el-button></template>
  </el-dialog>

  <el-dialog v-model="antOpen" title="导入 ant-platform 结果文件" width="560px">
    <el-alert
      type="info"
      :closable="false"
      title="上传 ant-platform 批量查询导出的 CSV。“成功”“无数据”的号码成为撞库结果并进入 30 天缓存；“失败”“待核对”的行不导入，也不会重新查询。"
    />
    <el-form label-position="top" style="margin-top: 12px"
      ><el-form-item label="合作方"
        ><el-select v-model="antForm.partnerId" style="width: 100%"
          ><el-option
            v-for="p in partners.data.value || []"
            :key="p.id"
            :label="p.name"
            :value="p.id" /></el-select></el-form-item
      ><el-form-item label="关联数据包版本（选填）"
        ><el-select v-model="antForm.versionId" clearable style="width: 100%"
          ><el-option
            v-for="v in ready.data.value || []"
            :key="v.id"
            :label="`${v.dataset_name} · v${v.version_no}`"
            :value="v.id" /></el-select></el-form-item
      ><el-form-item label="文件"><input ref="antInput" type="file" accept=".csv" /></el-form-item
    ></el-form>
    <template #footer><el-button type="primary" @click="importAnt">导入</el-button></template>
  </el-dialog>

  <el-drawer
    :model-value="analysis !== null"
    size="60%"
    title="撞库结果分析"
    @close="analysis = null"
  >
    <template v-if="analysis">
      <el-descriptions :column="3" border>
        <el-descriptions-item label="号码数">{{ analysis.job.total }}</el-descriptions-item>
        <el-descriptions-item label="已返回">{{ analysis.answered }}</el-descriptions-item>
        <el-descriptions-item label="命中">{{ analysis.hits }}</el-descriptions-item>
        <el-descriptions-item label="命中率">{{ analysis.hit_rate }}%</el-descriptions-item>
        <el-descriptions-item label="计费次数">{{
          analysis.job.counts.charged
        }}</el-descriptions-item>
        <el-descriptions-item label="复用缓存">{{
          analysis.job.counts.cached
        }}</el-descriptions-item>
        <el-descriptions-item label="与历史撞库重合"
          >{{ analysis.overlap }}（{{ analysis.overlap_rate }}%）</el-descriptions-item
        >
        <el-descriptions-item v-for="(n, g) in analysis.grades" :key="g" :label="`等级 ${g}`">{{
          n
        }}</el-descriptions-item>
      </el-descriptions>
      <h4>分数分布（命中号码）</h4>
      <div v-for="b in analysis.score_distribution" :key="b.range" class="bar-row">
        <span class="bar-label">{{ b.range }}</span
        ><span class="bar" :style="{ width: (b.count / maxBucket) * 70 + '%' }"></span
        ><span>{{ b.count }}</span>
      </div>
      <h4>最佳联系时段（首选时段）</h4>
      <div v-for="h in analysis.best_hours.filter((x) => x.count)" :key="h.hour" class="bar-row">
        <span class="bar-label">{{ String(h.hour).padStart(2, '0') }}:00</span
        ><span class="bar" :style="{ width: (h.count / maxHour) * 70 + '%' }"></span
        ><span>{{ h.count }}</span>
      </div>
      <h4 v-if="analysis.labels.length">标签</h4>
      <el-tag v-for="l in analysis.labels" :key="l.label" style="margin: 0 6px 6px 0"
        >{{ l.label }} {{ l.count }}</el-tag
      >
      <h4>与历史撞库的重合</h4>
      <DataGrid
        :rows="analysis.overlap_jobs"
        :columns="[
          { key: 'partner_name', label: '合作方' },
          { key: 'created_at', label: '提交时间', date: true },
          { key: 'overlap', label: '重合号码' },
          { key: 'overlap_hits', label: '其中当时命中' },
        ]"
      />
    </template>
  </el-drawer>
</template>
<style scoped>
.bar-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 4px 0;
}
.bar-label {
  width: 64px;
  color: var(--el-text-color-secondary);
}
.bar {
  display: inline-block;
  height: 12px;
  min-width: 2px;
  background: var(--el-color-primary);
  border-radius: 2px;
}
.muted {
  color: var(--el-text-color-secondary);
}
</style>
