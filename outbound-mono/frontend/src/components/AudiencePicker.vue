<script setup>
import { ref, watch } from 'vue';
import { request, download } from '../session';
import { useData } from '../data';
const props = defineProps({ model: Object, scriptId: [String, Number], templateId: String });
const batches = useData('/api/v1/workspace/contact-batches'),
  segments = useData('/api/v1/workspace/audiences/segments');
const page = ref(1),
  view = ref(null),
  error = ref(''),
  busy = ref(false);
let seq = 0;
const body = () => ({
  ...props.model,
  script_id: props.scriptId || '',
  template_id: props.templateId || '',
});
async function preview() {
  let n = ++seq;
  busy.value = true;
  error.value = '';
  try {
    const v = await request(
      '/api/v1/workspace/audiences/preview?page=' + page.value,
      'POST',
      body(),
      { timeoutMs: 120000 },
    );
    if (n === seq) view.value = v;
  } catch (e) {
    if (n === seq) error.value = e.message;
  } finally {
    if (n === seq) busy.value = false;
  }
}
function exclude(id) {
  props.model.excluded_contact_ids = [
    ...new Set([...(props.model.excluded_contact_ids || []), id]),
  ];
  preview();
}
async function exportList() {
  try {
    await download('/api/v1/workspace/audiences/export', '任务名单.csv', {
      method: 'POST',
      body: body(),
    });
  } catch (e) {
    error.value = e.message;
  }
}
watch(
  () => [
    props.model.batch_ids,
    props.model.contact_ids,
    props.model.segment_snapshot_ids,
    props.scriptId,
    props.templateId,
  ],
  () => {
    view.value = null;
    page.value = 1;
    seq++;
  },
  { deep: true },
);
defineExpose({ preview, view });
</script>
<template>
  <el-form label-position="top">
    <el-form-item label="导入批次"
      ><el-select v-model="model.batch_ids" multiple filterable aria-label="任务名单批次"
        ><el-option
          v-for="b in batches.data.value || []"
          :key="b.id"
          :value="b.id"
          :label="b.name + ' · ' + (b.result.selected_count || 0) + '人'" /></el-select
    ></el-form-item>
    <el-form-item label="已发布人群快照"
      ><el-select
        v-model="model.segment_snapshot_ids"
        multiple
        filterable
        aria-label="已发布人群快照"
        ><el-option
          v-for="s in segments.data.value || []"
          :key="s.id"
          :value="s.id"
          :label="s.name + ' · ' + s.test_size + '实验组成员'" /></el-select
      ><small
        >只选实验组且已关联客户的成员；对照组和未关联加密号码会列出数量，不自动触达。</small
      ></el-form-item
    >
  </el-form>
  <div class="actions">
    <el-button :loading="busy" @click="preview">预览有效名单</el-button
    ><el-button :disabled="!view?.total" @click="exportList">导出当前名单</el-button
    ><el-button
      v-if="model.excluded_contact_ids?.length"
      @click="
        model.excluded_contact_ids = [];
        preview();
      "
      >撤销排除（{{ model.excluded_contact_ids.length }}）</el-button
    >
  </div>
  <el-alert v-if="error" type="error" :title="error" />
  <template v-if="view"
    ><p>
      去重并排除后 {{ view.total }} 人；已排除 {{ view.excluded }}；对照组
      {{ view.control_group }}；未关联客户 {{ view.unlinked }}。预检标记可重叠：禁呼
      {{ view.flags.dnc }}、非已同意 {{ view.flags.not_consented }}、缺少变量
      {{ view.flags.missing_variables }}。启动时仍会检查最新授权及禁发状态。
    </p>
    <el-table :data="view.rows" v-loading="busy"
      ><el-table-column prop="name" label="姓名" /><el-table-column
        prop="phone"
        label="号码"
      /><el-table-column label="业务变量"
        ><template #default="{ row }">{{
          JSON.stringify(row.variables)
        }}</template></el-table-column
      ><el-table-column label="预检"
        ><template #default="{ row }"
          >{{ row.dnc ? '禁呼 ' : '' }}{{ row.consent_state !== 'consented' ? '未确认授权 ' : ''
          }}{{ row.missing_variables ? '缺少变量' : '' }}</template
        ></el-table-column
      ><el-table-column label="操作"
        ><template #default="{ row }"
          ><el-button link @click="exclude(row.id)">排除此客户</el-button></template
        ></el-table-column
      ></el-table
    ><el-pagination
      v-model:current-page="page"
      :page-size="50"
      :total="view.total"
      layout="prev,pager,next"
      @current-change="preview"
  /></template>
</template>
