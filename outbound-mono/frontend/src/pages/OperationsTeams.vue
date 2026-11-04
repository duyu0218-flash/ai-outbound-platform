<script setup>
import { ref, onMounted, reactive } from 'vue';
import { ops, perform, parse } from '../operations';
import { session, request } from '../session';
import SeatMonitor from '../components/SeatMonitor.vue';
import ResourceCatalog from '../components/ResourceCatalog.vue';
const orderKey =
  'seat-panel-order:' +
  JSON.stringify([location.pathname, session.user?.tenant_id, session.user?.id]);
const below = ref(localStorage.getItem(orderKey) === 'below'),
  dragging = ref(false);
function place(after) {
  below.value = after;
  localStorage.setItem(orderKey, after ? 'below' : 'above');
  dragging.value = false;
}
const skills = ref([]),
  teamStatus = ref([]);
async function catalog() {
  skills.value = await request('/api/v1/workspace/catalog/skill');
  teamStatus.value = await request('/api/v1/workspace/catalog/skill-status');
}
const rows = ref([]),
  profile = ref({}),
  error = ref(''),
  open = ref(false),
  model = reactive({}),
  policy = reactive({ can_export: false, can_unmask: false, can_record: true }),
  policyUser = ref(null);
async function load() {
  try {
    profile.value = await ops('/me');
    rows.value = await ops('/teams');
    await catalog();
  } catch (e) {
    error.value = e.message;
  }
}
function edit(row) {
  Object.keys(model).forEach((k) => delete model[k]);
  Object.assign(
    model,
    row
      ? {
          ...row,
          settings: parse(row.settings_json),
          members: row.members.map((m) => ({ user_id: m.user_id, is_lead: !!m.is_lead })),
        }
      : {
          name: '',
          enabled: true,
          settings: {
            routing_skills: [],
            after_call_seconds: 0,
            queue_timeout_seconds: 60,
            personal_override: false,
          },
          members: [],
        },
  );
  open.value = true;
}
async function loadPolicy() {
  if (!policyUser.value) return;
  try {
    const value = await ops('/permissions/' + policyUser.value);
    for (const k of ['can_export', 'can_unmask', 'can_record']) policy[k] = !!value[k];
  } catch (e) {
    error.value = e.message;
  }
}
async function save() {
  const result = await perform(() => ops('/teams', 'POST', model), load);
  if (result) open.value = false;
}
onMounted(load);
</script>
<template>
  <PageHeader title="团队与数据权限" description="组长查看本组，坐席查看本人；服务端同步校验"
    ><el-button type="primary" @click="edit()">新增坐席组</el-button></PageHeader
  >
  <div
    @dragover.prevent
    @drop.prevent="dragging && place(false)"
    style="min-height: 32px"
    aria-label="移至团队列表上方"
  >
    <span v-if="dragging">放在团队列表上方</span>
    <div v-if="!below">
      <button
        draggable="true"
        @dragstart="dragging = true"
        @dragend="dragging = false"
        @click="place(true)"
        aria-label="调整坐席面板位置"
      >
        ↕ 调整坐席面板位置</button
      ><SeatMonitor />
    </div>
  </div>
  <el-alert v-if="error" :title="error" type="error" /><el-card
    ><DataGrid
      :rows="rows"
      :columns="[
        { key: 'name', label: '组名' },
        { key: 'enabled', label: '启用' },
        { key: 'revision', label: '版本' },
        {
          key: 'members',
          label: '成员',
          render: (v) =>
            (v || []).map((m) => m.full_name + (m.is_lead ? '（组长）' : '')).join('、'),
        },
      ]"
      ><template #actions="{ row }"
        ><el-button link @click="edit(row)">编辑坐席组</el-button></template
      ></DataGrid
    ></el-card
  >
  <div
    @dragover.prevent
    @drop.prevent="dragging && place(true)"
    style="min-height: 32px"
    aria-label="移至团队列表下方"
  >
    <span v-if="dragging">放在团队列表下方</span>
    <div v-if="below">
      <button
        draggable="true"
        @dragstart="dragging = true"
        @dragend="dragging = false"
        @click="place(false)"
        aria-label="调整坐席面板位置"
      >
        ↕ 调整坐席面板位置</button
      ><SeatMonitor />
    </div>
  </div>
  <el-card header="用户操作权限"
    ><el-select v-model="policyUser" aria-label="权限用户" @change="loadPolicy"
      ><el-option
        v-for="u in profile.users || []"
        :key="u.id"
        :value="u.id"
        :label="u.full_name" /></el-select
    ><el-checkbox v-model="policy.can_export">允许导出</el-checkbox
    ><el-checkbox v-model="policy.can_unmask">显示完整号码</el-checkbox
    ><el-checkbox v-model="policy.can_record">访问录音</el-checkbox
    ><el-button
      :disabled="!policyUser"
      @click="perform(() => ops('/permissions/' + policyUser, 'PUT', policy), loadPolicy)"
      >保存操作权限</el-button
    >
    <p>
      管理员保留全租户管理权限。组长在成员表中设置，数据范围实时生效；普通坐席导出默认关闭，号码默认脱敏。
    </p></el-card
  ><ResourceCatalog kind="skill" @changed="catalog" /><el-card header="团队接待检查"
    ><el-table :data="teamStatus"
      ><el-table-column prop="name" label="团队" /><el-table-column
        prop="enabled_members"
        label="启用成员" /><el-table-column
        prop="reason"
        label="接待状态说明" /></el-table></el-card
  ><el-dialog v-model="open" title="坐席组配置" width="760px"
    ><el-form label-position="top"
      ><el-form-item label="组名"
        ><el-input v-model="model.name" aria-label="坐席组名" /></el-form-item
      ><el-form-item label="启用"
        ><el-switch v-model="model.enabled" aria-label="启用坐席组" /></el-form-item
      ><el-form-item label="成员"
        ><el-select
          :model-value="(model.members || []).map((m) => m.user_id)"
          multiple
          aria-label="坐席组成员"
          @change="
            model.members = $event.map(
              (id) =>
                model.members.find((m) => m.user_id === id) || { user_id: id, is_lead: false },
            )
          "
          ><el-option
            v-for="u in profile.users || []"
            :key="u.id"
            :value="u.id"
            :label="u.full_name"
            :disabled="!u.enabled" /></el-select
      ></el-form-item>
      <div v-for="m in model.members || []" :key="m.user_id" class="toolbar">
        <span>{{ profile.users?.find((u) => u.id === m.user_id)?.full_name }}</span
        ><el-checkbox v-model="m.is_lead">组长</el-checkbox>
      </div>
      <template v-if="model.settings"
        ><el-form-item label="接听技能"
          ><el-select
            v-model="model.settings.routing_skills"
            multiple
            filterable
            default-first-option
            aria-label="接听技能"
            placeholder="从技能字典选择"
            ><el-option
              v-for="s in skills"
              :key="s.object_id"
              :label="s.name"
              :value="s.name"
              :disabled="!s.enabled && !model.settings.routing_skills.includes(s.name)" /><el-option
              v-for="s in model.settings.routing_skills.filter(
                (s) => !skills.some((v) => v.name === s),
              )"
              :key="s"
              :label="s + '（历史技能）'"
              :value="s" /></el-select></el-form-item
        ><el-form-item label="通话后整理时长（秒）"
          ><el-input-number
            v-model="model.settings.after_call_seconds"
            :min="0"
            :max="600"
            aria-label="组整理秒数" /></el-form-item
        ><el-form-item label="最长等待接听时长（秒）"
          ><el-input-number
            v-model="model.settings.queue_timeout_seconds"
            :min="5"
            :max="600"
            aria-label="组转接等待秒数" /></el-form-item
        ><el-form-item label="允许个人覆盖"
          ><el-switch
            v-model="model.settings.personal_override"
            aria-label="允许个人坐席设置" /></el-form-item></template
      ><el-button type="primary" :disabled="!model.name" @click="save"
        >保存坐席组</el-button
      ></el-form
    ></el-dialog
  >
</template>
