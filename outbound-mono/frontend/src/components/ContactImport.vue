<script setup>
import { ref, watch, onUnmounted } from 'vue';
import { request, download, session } from '../session';
const props = defineProps({ modelValue: Boolean, selectForTask: Boolean });
const emit = defineEmits(['update:modelValue', 'imported']);
const mapping = ref({}),
  job = ref(null);
const file = ref(null),
  preview = ref(null),
  result = ref(null),
  error = ref(''),
  busy = ref(false),
  upsert = ref(false);
let key = '',
  disposed = false;
const epoch = session.epoch;
const current = () => !disposed && epoch === session.epoch;
onUnmounted(() => {
  disposed = true;
});
watch(
  () => props.modelValue,
  (open) => {
    if (open && !busy.value) {
      file.value = null;
      preview.value = null;
      result.value = null;
      error.value = '';
      upsert.value = false;
      key = '';
      job.value = null;
      mapping.value = {};
    }
  },
);
watch(upsert, () => {
  preview.value = null;
  key = '';
});
async function template() {
  try {
    await download('/api/v1/contacts/import/template', '外呼名单导入模板.xlsx');
  } catch (e) {
    error.value = e.message;
  }
}
async function inspect(event) {
  file.value = event.target.files?.[0] || null;
  preview.value = null;
  result.value = null;
  error.value = '';
  key = '';
  event.target.value = '';
  if (file.value) await submit(true);
}
async function submit(dryRun) {
  if (!file.value || busy.value) return;
  if (file.value.size > 20 * 1024 * 1024) {
    error.value = '文件不能超过 20MB';
    return;
  }
  busy.value = true;
  error.value = '';
  const body = new FormData();
  body.append('file', file.value);
  key ||= crypto.randomUUID();
  try {
    let value = await request(
      '/api/v1/workspace/contact-batches' +
        (dryRun ? '/preview' : '') +
        '?upsert=' +
        upsert.value +
        '&mapping=' +
        encodeURIComponent(
          JSON.stringify(
            Object.fromEntries(
              Object.entries(mapping.value)
                .filter(([, v]) => v)
                .map(([field, header]) => [header, field]),
            ),
          ),
        ),
      'POST',
      body,
      { headers: { 'Idempotency-Key': key }, timeoutMs: 600000 },
    );
    if (!current()) return;
    if (dryRun) preview.value = value;
    else {
      job.value = value;
      while (current() && ['queued', 'processing'].includes(value.state)) {
        await new Promise((resolve) => setTimeout(resolve, 1500));
        if (!current()) return;
        value = await request('/api/v1/workspace/contact-batches/uploads/' + value.id);
        job.value = value;
      }
      if (value.state === 'failed') throw Error(value.error || '导入失败');
      result.value = value.result;
      emit('imported', value.result);
    }
  } catch (e) {
    if (current())
      error.value =
        e.message + (dryRun ? '' : '。结果未确认时，可重试核对；本次导入使用同一请求标识。');
  } finally {
    if (current()) busy.value = false;
  }
}
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    title="导入外呼名单"
    width="min(900px, 95vw)"
    :close-on-click-modal="!busy"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-steps :active="result ? 3 : preview ? 2 : 0" simple
      ><el-step title="下载模板" /><el-step title="上传并预览" /><el-step title="确认导入"
    /></el-steps>
    <p>
      每行填写一个号码，手机号按文本保存。支持 .xlsx 和 UTF-8 CSV，最多
      20MB。导入仅保存名单，任务启动后才会拨打。
    </p>
    <div class="actions">
      <el-button :disabled="busy" @click="template">下载导入模板</el-button>
      <label class="contact-upload"
        >上传号码文件<input
          aria-label="上传号码文件"
          type="file"
          accept=".xlsx,.csv"
          :disabled="busy"
          @change="inspect"
      /></label>
    </div>
    <p v-if="file">已选择：{{ file.name }}</p>
    <el-form label-position="top"
      ><el-form-item label="平台已有号码的处理方式">
        <el-radio-group v-model="upsert" :disabled="busy || !!result"
          ><el-radio :value="false">跳过，保留原资料</el-radio
          ><el-radio :value="true">更新，使用文件中的资料覆盖</el-radio></el-radio-group
        >
        <small
          >同一文件中的重复号码只处理首次出现的一条。仅用非空单元格更新资料；空白不清除原值。同意状态与禁呼状态会在实际拨号前再次检查。</small
        >
      </el-form-item></el-form
    >
    <el-collapse
      ><el-collapse-item title="自定义列映射（使用平台模板无需填写）"
        ><p>填写您文件中的准确列名。手机号码列必填；其他列可留空，系统自动识别平台模板。</p>
        <el-form label-position="top"
          ><el-form-item
            v-for="[field, label] in [
              ['phone', '手机号码列'],
              ['name', '姓名列'],
              ['tags', '标签列'],
              ['consent_state', '同意状态列'],
              ['dnc', '禁止呼叫列'],
              ['dnc_reason', '禁呼原因列'],
              ['timezone', '时区列'],
            ]"
            :key="field"
            :label="label"
            ><el-input
              v-model="mapping[field]"
              :aria-label="label"
              :disabled="busy"
              @input="
                preview = null;
                key = '';
              " /></el-form-item></el-form></el-collapse-item
    ></el-collapse>
    <p v-if="job">
      导入任务：{{ job.id }} ·
      {{
        { queued: '排队中', processing: '处理中', completed: '已完成', failed: '失败' }[job.state]
      }}。已处理 {{ job.result?.processed_rows || 0 }} /
      {{ job.result?.total || '—' }} 行，可在导入批次页查看或恢复。
    </p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-loading="busy">
      <template v-if="result || preview">
        <el-descriptions :title="result ? '导入结果' : '导入预览（尚未保存）'" :column="3" border>
          <el-descriptions-item
            v-for="[field, label] in [
              ['total', '总行数'],
              ['created', '新增'],
              ['updated', '更新'],
              ['skipped', '跳过'],
              ['failed', '失败'],
            ]"
            :key="field"
            :label="label"
            >{{ (result || preview)[field] }}</el-descriptions-item
          >
        </el-descriptions>
        <el-table :data="(result || preview).preview_rows" max-height="260"
          ><el-table-column prop="row" label="文件行号" width="100" /><el-table-column
            prop="phone"
            label="号码" /><el-table-column prop="name" label="姓名" /><el-table-column
            prop="action"
            label="处理方式"
        /></el-table>
        <small>预览最多显示前 50 条有效记录；以上统计覆盖整个文件。</small>
        <el-collapse v-if="(result || preview).errors?.length"
          ><el-collapse-item title="查看失败与重复明细"
            ><p v-for="(item, i) in (result || preview).errors" :key="i">
              {{ item }}
            </p></el-collapse-item
          ></el-collapse
        >
      </template>
      <el-empty v-else-if="!busy" description="下载模板填写号码，再上传查看预览" />
    </div>
    <el-alert
      v-if="result"
      :title="
        selectForTask
          ? `已将 ${result.selected_count || 0} 个联系人加入当前任务名单，请继续保存任务。`
          : '导入已完成，可在客户名单中查看。'
      "
      type="success"
      :closable="false"
    />
    <el-button
      v-if="result?.batch_id"
      @click="
        download(
          '/api/v1/workspace/contact-batches/' + result.batch_id + '/errors',
          '导入错误明细.csv',
        )
      "
      >下载错误明细</el-button
    >
    <template #footer>
      <el-button :disabled="busy" @click="emit('update:modelValue', false)">{{
        result ? '完成' : '取消'
      }}</el-button>
      <el-button v-if="file && !preview && !result" :loading="busy" @click="submit(true)"
        >重新预览</el-button
      >
      <el-button
        v-if="preview && !result"
        type="primary"
        :disabled="busy || !(preview.created + preview.updated + preview.skipped > 0)"
        :loading="busy"
        @click="submit(false)"
        >确认导入</el-button
      >
    </template>
  </el-dialog>
</template>
<style scoped>
.contact-upload {
  display: inline-flex;
  gap: 12px;
  align-items: center;
}
small {
  display: block;
  color: #64748b;
  margin: 8px 0;
}
.el-alert {
  margin: 14px 0;
}
</style>
