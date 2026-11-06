<script setup>
import { ref, watch } from 'vue';
import { useData } from '../data';
import { ops, perform } from '../operations';
const props = defineProps({ accountsOnly: Boolean });
const requestPage = ref(1),
  requestSize = ref(20),
  requestState = ref(''),
  requestTotal = ref(0);
const accounts = useData('/api/v1/ops/wecom/accounts'),
  providers = useData(() => (props.accountsOnly ? '' : '/api/v1/ops/wecom/providers')),
  requests = useData(() =>
    props.accountsOnly
      ? ''
      : '/api/v1/ops/wecom/requests?page=' +
        requestPage.value +
        '&size=' +
        requestSize.value +
        '&state=' +
        requestState.value,
  ),
  open = ref(false),
  kind = ref('account'),
  id = ref(''),
  revision = ref(0),
  model = ref({}),
  secret = ref('');
watch(requests.data, (result) => {
  // Keep the last count while a new page loads; a temporary zero resets the current page.
  if (result) requestTotal.value = Number(result.total || 0);
});
function edit(type, row) {
  kind.value = type;
  id.value = row?.object_id || (type === 'provider' ? crypto.randomUUID() : '');
  revision.value = row?.revision || 0;
  model.value = row
    ? structuredClone(row.draft)
    : type === 'account'
      ? { name: '' }
      : { name: '', app_key: '', rule_id: '', version: '1', endpoint: '', enabled: false };
  secret.value = '';
  open.value = true;
}
async function save() {
  const saved = await perform(
    () =>
      ops('/wecom/' + (kind.value === 'account' ? 'accounts/' : 'providers/') + id.value, 'PUT', {
        revision: revision.value,
        config: model.value,
        app_secret: secret.value,
      }),
    kind.value === 'account' ? accounts.load : providers.load,
  );
  if (saved) open.value = false;
}
async function remove(row) {
  await perform(
    () => ops('/wecom/accounts/' + row.object_id + '?revision=' + row.revision, 'DELETE'),
    accounts.load,
  );
}
</script>
<template>
  <el-card v-if="accountsOnly" header="搜索企微">
    <p>
      每个公司最多100个微信号；微信号为11位数字，微信名最多128个字符。已添加
      {{ accounts.data.value?.length || 0 }} 个。
    </p>
    <el-button type="primary" @click="edit('account')">添加微信号</el-button>
    <el-alert v-if="accounts.error.value" :title="accounts.error.value" type="error" />
    <el-table :data="accounts.data.value || []"
      ><el-table-column prop="object_id" label="微信号" /><el-table-column
        prop="name"
        label="微信名"
      /><el-table-column label="操作"
        ><template #default="{ row }"
          ><el-button link @click="edit('account', row)">编辑</el-button
          ><el-button link @click="remove(row)">删除</el-button></template
        ></el-table-column
      ></el-table
    >
  </el-card>
  <el-card v-else header="添加配置">
    <el-button type="primary" @click="edit('provider')">添加第三方配置</el-button>
    <el-alert v-if="providers.error.value" :title="providers.error.value" type="error" />
    <el-table :data="providers.data.value || []"
      ><el-table-column prop="name" label="第三方" /><el-table-column
        prop="draft.app_key"
        label="app_key"
      /><el-table-column label="app_secret"
        ><template #default="{ row }">{{
          row.draft.secret_configured ? '已配置' : '未配置'
        }}</template></el-table-column
      ><el-table-column prop="draft.rule_id" label="rule_id" /><el-table-column
        prop="draft.version"
        label="版本"
      /><el-table-column label="操作"
        ><template #default="{ row }"
          ><el-button link @click="edit('provider', row)">编辑</el-button></template
        ></el-table-column
      ></el-table
    >
    <h3>自动添加请求</h3>
    <div class="actions">
      <el-button @click="requests.load">刷新请求</el-button>
      <el-select
        v-model="requestState"
        aria-label="请求状态"
        style="width: 180px"
        @change="requestPage = 1"
      >
        <el-option label="全部请求" value="" />
        <el-option label="待服务商受理" value="pending" />
        <el-option label="服务商已受理" value="accepted" />
      </el-select>
    </div>
    <p>服务商受理后仍需客户确认；通过率使用实际登记结果。请求历史按时间分页查询。</p>
    <el-alert v-if="requests.error.value" :title="requests.error.value" type="error" />
    <el-table :data="requests.data.value?.rows || []"
      ><el-table-column prop="object_id" label="请求ID" min-width="180" /><el-table-column
        prop="updated_at"
        label="更新时间"
        min-width="160"
      /><el-table-column prop="draft.phone" label="客户号码" /><el-table-column
        prop="draft.wecom_id"
        label="随机选择微信号"
      /><el-table-column label="状态"
        ><template #default="{ row }">{{
          row.draft.state === 'accepted' ? '服务商已受理，待确认' : '待服务商受理'
        }}</template></el-table-column
      ></el-table
    >
    <el-pagination
      v-model:current-page="requestPage"
      v-model:page-size="requestSize"
      :page-sizes="[20, 50, 100, 200]"
      :total="requestTotal"
      layout="total, sizes, prev, pager, next, jumper"
      @size-change="requestPage = 1"
    />
  </el-card>
  <el-dialog
    v-model="open"
    :title="kind === 'account' ? '添加微信号' : '第三方添加配置'"
    destroy-on-close
  >
    <el-form label-position="top"
      ><el-form-item v-if="kind === 'account'" label="微信号"
        ><el-input
          v-model="id"
          :disabled="revision > 0"
          maxlength="11"
          aria-label="微信号" /></el-form-item
      ><el-form-item :label="kind === 'account' ? '微信名' : '第三方'"
        ><el-input v-model="model.name" maxlength="128" aria-label="企微配置名称"
      /></el-form-item>
      <template v-if="kind === 'provider'"
        ><el-form-item
          v-for="key in ['app_key', 'rule_id', 'version', 'endpoint']"
          :key="key"
          :label="key === 'endpoint' ? '服务商桥接地址' : key"
          ><el-input v-model="model[key]" :aria-label="'企微' + key" /></el-form-item
        ><el-form-item label="app_secret"
          ><el-input
            v-model="secret"
            type="password"
            show-password
            autocomplete="new-password"
            aria-label="企微app_secret"
            :placeholder="
              model.secret_configured ? '已配置，留空保留' : '16至256个字符'
            " /></el-form-item
        ><el-form-item label="启用"
          ><el-switch
            v-model="model.enabled"
            aria-label="启用企微服务商" /></el-form-item></template
      ><el-button type="primary" @click="save">保存企微添加配置</el-button>
    </el-form>
  </el-dialog>
</template>
