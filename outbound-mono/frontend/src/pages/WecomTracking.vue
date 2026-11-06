<script setup>
import { ref, watch } from 'vue';
import WecomConfiguration from '../components/WecomConfiguration.vue';
import { session } from '../session';
import { useData, action } from '../data';
import { ops, perform } from '../operations';
const date = new Date(Date.now() + 8 * 3600000).toISOString().slice(0, 10);
const statFrom = ref(date.slice(0, 7) + '-01'),
  statUntil = ref(date),
  statUser = ref(''),
  statApplied = ref({}),
  staff = useData('/api/v1/ops/me');
function applyStats() {
  if (statFrom.value && statUntil.value && statFrom.value <= statUntil.value)
    statApplied.value = {
      from: statFrom.value + 'T00:00:00+08:00',
      until: statUntil.value + 'T23:59:59.999+08:00',
      actor_id: statUser.value,
    };
}
const tab = ref('statistics'),
  statistics = useData(
    () => '/api/v1/ops/wecom/statistics?' + new URLSearchParams(statApplied.value),
  ),
  profile = useData('/api/v1/ops/wecom/profiles/' + session.user.id),
  config = ref({}),
  revision = ref(0);
watch(
  () => profile.data.value,
  (v) => {
    if (v) {
      config.value = JSON.parse(JSON.stringify(v.draft));
      revision.value = v.revision;
    }
  },
);
async function saveProfile() {
  await perform(
    () =>
      ops('/wecom/profiles/' + session.user.id, 'PUT', {
        config: config.value,
        revision: revision.value,
      }),
    profile.load,
  );
}
const keyword = ref(''),
  page = ref(1),
  selected = ref(null),
  status = ref('pending'),
  note = ref('');
const labels = { pending: '待添加', added: '已添加', declined: '已拒绝' };
const rows = useData(
  () =>
    '/api/v1/ops/leads?view=mine&size=30&page=' +
    page.value +
    '&keyword=' +
    encodeURIComponent(keyword.value),
);
function edit(row) {
  selected.value = row;
  status.value = row.wecom_status;
  note.value = '';
}
async function save() {
  const row = selected.value;
  const saved = await action(
    () =>
      ops('/leads/' + row.contact_id, 'PATCH', {
        action: 'wecom',
        wecom_status: status.value,
        note: note.value,
        event_key: crypto.randomUUID(),
        revision: row.revision,
      }),
    { refresh: rows.load },
  );
  if (saved) selected.value = null;
}
</script>
<template>
  <PageHeader title="企微添加" description="登记本人跟进线索的企业微信添加结果" /><el-alert
    v-if="rows.error.value"
    type="error"
    :title="rows.error.value"
  />
  <el-tabs v-model="tab"
    ><el-tab-pane label="添加统计" name="statistics" /><el-tab-pane
      label="企微配置"
      name="config" /><el-tab-pane label="搜索企微" name="search"
  /></el-tabs>
  <el-card v-if="tab === 'statistics'"
    ><el-button @click="statistics.load">刷新统计</el-button
    ><el-alert
      v-if="statistics.error.value"
      :title="statistics.error.value"
      type="error" /><el-table :data="statistics.data.value || []"
      ><el-table-column prop="date" label="日期" /><el-table-column
        prop="agent"
        label="坐席" /><el-table-column prop="requested" label="已添加数量" /><el-table-column
        prop="added"
        label="通过数量" /><el-table-column prop="approval_rate" label="通过率（%）" /></el-table
  ></el-card>
  <template v-else-if="tab === 'config'"
    ><WecomConfiguration /><el-card
      ><el-alert v-if="profile.error.value" :title="profile.error.value" type="error" /><el-form
        label-position="top"
        ><el-form-item
          v-for="[key, label] in [
            ['corp_id', '企业ID'],
            ['member_id', '企微成员账号'],
            ['contact_url', '联系客户链接'],
          ]"
          :key="key"
          :label="label"
          ><el-input v-model="config[key]" :aria-label="label" /></el-form-item
        ><el-button
          type="primary"
          :disabled="!config.corp_id || !config.member_id"
          @click="saveProfile"
          >保存企微配置</el-button
        ></el-form
      ></el-card
    ></template
  >
  <template v-else
    ><WecomConfiguration accounts-only />
    <el-card
      ><el-input
        aria-label="查找企微线索"
        v-model="keyword"
        placeholder="按客户姓名或号码搜索"
        clearable
        @input="page = 1"
      />
      <el-table :data="rows.data.value?.rows || []" row-key="contact_id"
        ><el-table-column prop="name" label="客户" /><el-table-column
          prop="phone"
          label="电话"
        /><el-table-column label="添加结果"
          ><template #default="{ row }">{{ labels[row.wecom_status] }}</template></el-table-column
        ><el-table-column label="操作"
          ><template #default="{ row }"
            ><a
              v-if="config.contact_url"
              :href="config.contact_url"
              target="_blank"
              rel="noopener noreferrer"
              >在企业微信打开</a
            ><el-button @click="edit(row)">登记添加结果</el-button></template
          ></el-table-column
        ></el-table
      >
      <el-pagination
        v-model:current-page="page"
        :page-size="30"
        :total="rows.data.value?.total || 0"
        layout="prev,pager,next,total"
      /> </el-card></template
  ><el-dialog :model-value="!!selected" title="登记添加结果" @close="selected = null"
    ><el-form label-position="top"
      ><el-form-item label="添加结果"
        ><el-select v-model="status" aria-label="企微添加结果"
          ><el-option
            v-for="(v, k) in labels"
            :key="k"
            :value="k"
            :label="v" /></el-select></el-form-item
      ><el-form-item label="备注"
        ><el-input
          v-model="note"
          aria-label="企微添加备注"
          maxlength="2000" /></el-form-item></el-form
    ><template #footer><el-button @click="save">保存结果</el-button></template></el-dialog
  >
</template>
