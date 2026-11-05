<script setup>
import { ref } from 'vue';
import { useData } from '../data';
import { ops, perform } from '../operations';
const data = useData('/api/v1/ops/benchmark/accounts'),
  selected = ref(null),
  model = ref({}),
  amount = ref(0),
  note = ref(''),
  busy = ref(false);
function edit(row) {
  selected.value = row;
  model.value = JSON.parse(JSON.stringify(row.profile.draft));
}
async function save() {
  busy.value = true;
  try {
    const v = await perform(
      () =>
        ops('/benchmark/accounts/' + selected.value.id, 'PUT', {
          revision: selected.value.profile.revision,
          config: model.value,
        }),
      data.load,
    );
    if (v) selected.value = null;
  } finally {
    busy.value = false;
  }
}
async function allocate() {
  busy.value = true;
  try {
    await perform(
      () =>
        ops('/benchmark/accounts/' + selected.value.id + '/allocate', 'POST', {
          amount: amount.value,
          note: note.value,
          request_key: crypto.randomUUID(),
        }),
      data.load,
    );
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <el-alert v-if="data.error.value" :title="data.error.value" type="error" /><el-table
    :data="data.data.value || []"
    border
    ><el-table-column
      v-for="[k, l] in [
        ['id', 'ID'],
        ['full_name', '姓名'],
        ['username', '登录名'],
        ['role', '角色'],
        ['concurrency', '并发数量'],
        ['can_export', '导出功能权限'],
        ['can_unmask', '号码显示'],
        ['enabled', '正常/冻结'],
        ['balance', '子账号额度余额'],
      ]"
      :key="k"
      :prop="k"
      :label="l"
      min-width="135"
    /><el-table-column label="坐席到期时间" min-width="170"
      ><template #default="{ row }">{{
        row.profile.draft.expires_at || '不限'
      }}</template></el-table-column
    ><el-table-column label="操作" fixed="right"
      ><template #default="{ row }"
        ><el-button link @click="edit(row)">资源设置</el-button></template
      ></el-table-column
    ></el-table
  ><el-dialog :model-value="!!selected" title="账号资源" width="560px" @close="selected = null"
    ><template v-if="selected"
      ><el-form label-position="top"
        ><el-form-item label="到期时间（含时区）"
          ><el-input
            v-model="model.expires_at"
            aria-label="坐席到期时间"
            placeholder="2027-01-01T00:00:00+08:00" /></el-form-item
        ><el-form-item label="启用子账号余额控制"
          ><el-switch v-model="model.balance_enabled" aria-label="子账号余额控制" /></el-form-item
        ><el-button type="primary" :loading="busy" @click="save">保存账号资源</el-button
        ><el-divider>内部分配与回收额度</el-divider>
        <p>子账号额度来自已导入的租户语音余额，分配为正数，回收为负数。</p>
        <el-form-item label="金额（元）"
          ><el-input-number v-model="amount" :precision="2" aria-label="分配金额" /></el-form-item
        ><el-form-item label="备注"><el-input v-model="note" aria-label="分配备注" /></el-form-item
        ><el-button :disabled="busy || !amount || !note.trim()" @click="allocate"
          >记入额度流水</el-button
        ></el-form
      ></template
    ></el-dialog
  >
</template>
