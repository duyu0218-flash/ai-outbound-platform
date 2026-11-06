<script setup>
import { computed, ref } from 'vue';
import { useData } from '../data';
import { ops, perform } from '../operations';
import { ElMessageBox } from 'element-plus';
const props = defineProps({ type: String });
const list = useData(() => '/api/v1/ops/benchmark/strategies/' + props.type);
const tasks = useData(() =>
  props.type === 'achievement' ? '/api/v1/workspace/tasks?size=200' : '',
);
const open = ref(false),
  model = ref({}),
  id = ref(''),
  revision = ref(0);
const title = computed(
  () =>
    ({ frequency: '呼频规则', evaluation: '评估规则', achievement: '意向达成率规则' })[props.type],
);
function edit(row) {
  id.value = row?.object_id || crypto.randomUUID();
  revision.value = row?.revision || 0;
  model.value = row
    ? JSON.parse(JSON.stringify(row.draft))
    : {
        name: '',
        enabled: true,
        hours: 48,
        limit: 1,
        condition: 'all',
        default_rule: false,
        metric: 'connection',
        minimum_connected: 100,
        recommend: 30,
        reject: 10,
        rate: 5,
        campaign_id: 0,
      };
  open.value = true;
}
async function save() {
  const value = await perform(
    () =>
      ops('/benchmark/strategies/' + props.type + '/' + id.value, 'PUT', {
        config: model.value,
        revision: revision.value,
      }),
    list.load,
  );
  if (value) open.value = false;
}
async function remove(row) {
  try {
    await ElMessageBox.confirm('删除后后续任务不再使用此规则。', '删除规则');
  } catch {
    return;
  }
  await perform(
    () =>
      ops(
        '/benchmark/strategies/' + props.type + '/' + row.object_id + '?revision=' + row.revision,
        'DELETE',
      ),
    list.load,
  );
}
</script>
<template>
  <div class="toolbar">
    <el-button type="primary" @click="edit()">{{
      type === 'evaluation' ? '创建规则' : '新建规则'
    }}</el-button
    ><el-button @click="list.load">刷新规则</el-button>
  </div>
  <el-alert v-if="list.error.value" type="error" :title="list.error.value" />
  <el-table :data="list.data.value || []" border>
    <el-table-column prop="name" label="规则名称" />
    <template v-if="type === 'frequency'"
      ><el-table-column prop="draft.hours" label="周期（小时）" /><el-table-column
        prop="draft.limit"
        label="限制外呼次数"
      /><el-table-column label="拦截条件"
        ><template #default="{ row }">{{
          { all: '所有', answered: '已接通', unanswered: '未接通' }[row.draft.condition]
        }}</template></el-table-column
      ><el-table-column label="默认规则"
        ><template #default="{ row }">{{
          row.draft.default_rule ? '是' : '否'
        }}</template></el-table-column
      ></template
    >
    <template v-else-if="type === 'evaluation'"
      ><el-table-column label="评估指标"
        ><template #default="{ row }">{{
          { connection: '接通率', intent: '意向率', success: '成功率' }[row.draft.metric]
        }}</template></el-table-column
      ><el-table-column prop="draft.minimum_connected" label="接通样本数量" /><el-table-column
        label="建议 / 不建议（%）"
        ><template #default="{ row }"
          >≥ {{ row.draft.recommend }} / ≤ {{ row.draft.reject }}</template
        ></el-table-column
      ></template
    >
    <template v-else
      ><el-table-column label="任务"
        ><template #default="{ row }">{{
          tasks.data.value?.rows?.find((t) => t.id === row.draft.campaign_id)?.name || '所有任务'
        }}</template></el-table-column
      ><el-table-column
        prop="draft.minimum_connected"
        label="开启限制（呼通数量）" /><el-table-column
        prop="draft.rate"
        label="最低意向达成率（%）"
    /></template>
    <el-table-column label="状态"
      ><template #default="{ row }">{{
        row.draft.enabled ? '生效' : '停用'
      }}</template></el-table-column
    >
    <el-table-column label="操作"
      ><template #default="{ row }"
        ><el-button link @click="edit(row)">编辑</el-button
        ><el-button link type="danger" @click="remove(row)">删除</el-button></template
      ></el-table-column
    >
  </el-table>
  <p v-if="type === 'evaluation'">
    达到最少接通样本后，按建议、谨慎、不建议三档评估；数量不足显示“样本不足”。
  </p>
  <p v-if="type === 'achievement'">
    达到呼通数量后，低于意向达成率会自动暂停任务，保留待拨号码。在途通话继续完成。修改规则后下一次派号生效。
  </p>
  <el-dialog v-model="open" :title="title" width="560px" destroy-on-close
    ><el-form label-position="top">
      <el-form-item label="规则名称"
        ><el-input v-model="model.name" aria-label="规则名称" maxlength="160"
      /></el-form-item>
      <template v-if="type === 'frequency'"
        ><el-form-item label="周期（小时）"
          ><el-input-number
            v-model="model.hours"
            :min="1"
            :max="720"
            aria-label="呼频周期" /></el-form-item
        ><el-form-item label="限制外呼次数"
          ><el-input-number
            v-model="model.limit"
            :min="1"
            :max="1000"
            aria-label="限制外呼次数" /></el-form-item
        ><el-form-item label="拦截条件"
          ><el-select v-model="model.condition" aria-label="拦截条件"
            ><el-option label="所有" value="all" /><el-option
              label="已接通"
              value="answered" /><el-option
              label="未接通"
              value="unanswered" /></el-select></el-form-item
        ><el-form-item label="设为默认规则"
          ><el-switch v-model="model.default_rule" aria-label="设为默认规则" /></el-form-item
      ></template>
      <template v-else-if="type === 'evaluation'"
        ><el-form-item label="评估指标"
          ><el-select v-model="model.metric" aria-label="评估指标"
            ><el-option label="接通率" value="connection" /><el-option
              label="意向率"
              value="intent" /><el-option
              label="成功率"
              value="success" /></el-select></el-form-item
        ><el-form-item label="建议外呼 ≥（%）"
          ><el-input-number
            v-model="model.recommend"
            :min="1"
            :max="100"
            aria-label="建议外呼阈值" /></el-form-item
        ><el-form-item label="不建议外呼 ≤（%）"
          ><el-input-number v-model="model.reject" :min="0" :max="99" aria-label="不建议外呼阈值"
        /></el-form-item>
        <p>两个阈值之间为谨慎外呼。</p></template
      >
      <el-form-item v-if="type !== 'frequency'" label="接通数量（不足时显示样本不足）"
        ><el-input-number
          v-model="model.minimum_connected"
          :min="1"
          :max="1000000"
          aria-label="规则接通数量"
      /></el-form-item>
      <template v-if="type === 'achievement'"
        ><el-form-item label="任务（空值表示所有任务）"
          ><el-select v-model="model.campaign_id" aria-label="达成率任务"
            ><el-option :value="0" label="所有任务" /><el-option
              v-for="t in tasks.data.value?.rows || []"
              :key="t.id"
              :value="t.id"
              :label="t.name" /></el-select></el-form-item
        ><el-form-item label="意向达成率（%）"
          ><el-input-number
            v-model="model.rate"
            :min="0"
            :max="100"
            aria-label="意向达成率阈值" /></el-form-item
      ></template>
      <el-form-item label="是否生效"
        ><el-switch v-model="model.enabled" aria-label="规则生效"
      /></el-form-item>
      <el-button @click="open = false">取消</el-button
      ><el-button type="primary" :disabled="!model.name.trim()" @click="save">确定</el-button>
    </el-form></el-dialog
  >
</template>
