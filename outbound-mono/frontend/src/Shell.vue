<script setup>
import { computed, ref, watch, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import SupportDesk from './components/SupportDesk.vue';
import { adminMenu, operatorNavigation } from './router';
import { apiRequest } from './api';
import { session, logout, request } from './session';
import { useData, action } from './data';
import { t, language, setLanguage } from './i18n';
const props = defineProps({ role: String }),
  route = useRoute(),
  router = useRouter(),
  collapsed = ref(false),
  healthy = ref(null);
const dial = useData(
  () => (['admin', 'operator'].includes(session.user?.role) ? '/api/v1/ops/dial-control' : ''),
  15000,
);
async function switchCalls() {
  if (!dial.data.value) return;
  await action(
    () =>
      request('/api/v1/ops/dial-control', 'PUT', {
        enabled: !dial.data.value.enabled,
        revision: dial.data.value.revision,
      }),
    {
      confirm: dial.data.value.enabled
        ? '停止本租户新派发？已获得资源的通话可继续。'
        : '恢复本租户新派发？',
      refresh: dial.load,
    },
  );
}
const unread = useData('/api/v1/ops/notices/unread', 60000);
watch(() => route.path, unread.load);
const opsProfile = ref(null),
  groupOpen = ref({}),
  helpOpen = ref(false),
  supportOpen = ref(false);
onMounted(() =>
  request('/api/v1/ops/me')
    .then((v) => (opsProfile.value = v))
    .catch(() => {}),
);
const agentPath = (p) => '/agent/operations/' + p;
const navigation = computed(() =>
  props.role === 'admin'
    ? adminMenu.map(({ group, items }) => ({
        group,
        items: items.map(([path, label]) => ({
          path: '/admin' + (path ? '/' + path : ''),
          label,
        })),
      }))
    : [
        {
          group: 'navWorkbench',
          items: [{ path: '/agent', label: 'workspace' }],
        },
        {
          group: 'navDialing',
          items: [
            ...(session.user?.role === 'operator'
              ? operatorNavigation.map(([path, label]) => ({ path: '/agent/' + path, label }))
              : []),
            { path: '/agent/calls', label: 'calls' },
            { path: agentPath('calls'), label: '话单查询' },
          ],
        },
        {
          group: 'navQuality',
          items: [
            ...(session.user?.is_supervisor && opsProfile.value?.all_scope
              ? [{ path: '/agent/quality', label: 'qualityReview' }]
              : []),
            { path: agentPath('leads'), label: '线索与跟进' },
            { path: agentPath('shared-scripts'), label: '收到的话术' },
            { path: agentPath('wecom'), label: '企微添加' },
          ],
        },
        {
          group: 'navAnalysis',
          items: [
            { path: agentPath('performance'), label: '快捷面板' },
            { path: agentPath('overview'), label: '数据统计' },
            { path: agentPath('reports'), label: '运营报表' },
          ],
        },
        { group: 'navPersonal', items: [{ path: agentPath('workspace'), label: '坐席个人配置' }] },
      ],
);
watch(
  () => route.path,
  () =>
    apiRequest('/healthz')
      .then(() => (healthy.value = true))
      .catch(() => (healthy.value = false)),
  { immediate: true },
);
const openPages = ref([]),
  refreshIndex = ref(0);
watch(
  () => route.fullPath,
  () => {
    const existing = openPages.value.find((item) => item.path === route.path);
    if (existing) existing.url = route.fullPath;
    else
      openPages.value.push({
        path: route.path,
        url: route.fullPath,
        label: route.meta.label || 'dashboard',
      });
  },
  { immediate: true },
);
async function closePage(item) {
  const index = openPages.value.indexOf(item);
  if (index < 0 || openPages.value.length === 1) return;
  if (item.path === route.path) {
    const next = openPages.value[index - 1] || openPages.value[index + 1];
    if (await router.push(next.url)) return;
  }
  openPages.value = openPages.value.filter((page) => page.path !== item.path);
}
async function leave() {
  try {
    await logout();
  } finally {
    router.replace('/' + props.role + '/login');
  }
}
</script>
<template>
  <el-container class="app-shell"
    ><el-aside :width="collapsed ? '76px' : '232px'" class="sidebar"
      ><a class="brand" :href="'/' + role" @click.prevent="router.push('/' + role)"
        ><b>◉</b
        ><span v-if="!collapsed"
          >{{ t('product')
          }}<small>{{ t(role === 'admin' ? 'adminPortal' : 'agentPortal') }}</small></span
        ></a
      ><el-menu
        router
        :default-active="route.path"
        :collapse="collapsed"
        background-color="#0b1736"
        text-color="#c6d2ee"
        active-text-color="#fff"
        ><el-menu-item-group v-for="section in navigation" :key="section.group"
          ><template #title
            ><button
              v-if="!collapsed"
              class="nav-group nav-toggle"
              :aria-label="'展开或收起' + t(section.group)"
              :aria-expanded="groupOpen[section.group] !== false"
              @click="groupOpen[section.group] = groupOpen[section.group] === false"
            >
              {{ t(section.group) }}</button
            ><span v-else class="nav-group-rule"></span></template
          ><el-menu-item
            v-for="item in section.items"
            :key="item.path"
            :index="item.path"
            v-show="collapsed || groupOpen[section.group] !== false"
            ><el-icon><span class="nav-dot"></span></el-icon
            ><template #title>{{ t(item.label) }}</template></el-menu-item
          ></el-menu-item-group
        ></el-menu
      >
      <footer v-if="!collapsed">
        {{ t('tenant') }} #{{ session.user?.tenant_id }} · {{ session.user?.role }}
      </footer></el-aside
    ><el-container
      ><el-header class="app-header"
        ><div class="actions">
          <el-button aria-label="收起导航" @click="collapsed = !collapsed">☰</el-button
          ><strong>{{ t(route.meta.label || 'dashboard') }}</strong>
        </div>
        <div class="actions">
          <template v-if="dial.data.value"
            ><el-button link :disabled="session.user?.role !== 'admin'" @click="switchCalls"
              >呼叫开关 {{ dial.data.value.enabled ? '开' : '关' }}</el-button
            ><span :title="dial.data.value.updated_at">实时并发 {{ dial.data.value.active }}</span
            ><span v-if="dial.data.value.voice_balance" aria-label="语音余额"
              >{{ dial.data.value.voice_balance.mode === 'simulation' ? '模拟余额' : '语音余额' }}
              {{
                dial.data.value.voice_balance.available
                  ? dial.data.value.voice_balance.amount + '元'
                  : '未接入'
              }}</span
            ></template
          >
          <el-tag :type="healthy === false ? 'danger' : 'success'">{{
            t(healthy === false ? 'offline' : 'online')
          }}</el-tag
          ><el-select
            :model-value="language"
            aria-label="语言"
            class="language"
            @change="setLanguage"
            ><el-option label="中文" value="zh-CN" /><el-option
              label="English"
              value="en-US" /></el-select
          ><el-badge :value="unread.data.value?.count" :hidden="!unread.data.value?.count" :max="99"
            ><el-button
              link
              aria-label="通知中心"
              @click="router.push('/' + role + '/operations/notices')"
              >🔔</el-button
            ></el-badge
          >
          <el-button link @click="router.push('/' + role + '/operations/workspace')"
            >个人配置</el-button
          >
          <el-button link @click="supportOpen = true">客服</el-button
          ><el-button link @click="helpOpen = true">帮助</el-button>
          <span>{{ session.user?.full_name || session.user?.username }}</span
          ><el-button @click="leave">{{ t('logout') }}</el-button>
        </div></el-header
      ><el-main>
        <nav class="workspace-pages" aria-label="已打开页面">
          <span v-for="item in openPages" :key="item.path">
            <el-button
              link
              :type="item.path === route.path ? 'primary' : 'default'"
              @click="router.push(item.url)"
              >{{ t(item.label) }}</el-button
            >
            <el-button
              v-if="openPages.length > 1"
              link
              :aria-label="'关闭' + t(item.label)"
              @click="closePage(item)"
              >×</el-button
            >
          </span>
          <el-button link aria-label="刷新当前页面" @click="refreshIndex++">刷新</el-button>
        </nav>
        <router-view
          :key="route.path + ':' + session.epoch + ':' + refreshIndex" /></el-main></el-container
  ></el-container>
  <SupportDesk v-if="supportOpen" @close="supportOpen = false" /><el-drawer
    v-if="helpOpen"
    v-model="helpOpen"
    title="操作帮助"
    size="420px"
  >
    <template v-if="role === 'admin'">
      <h3>创建一次外呼任务</h3>
      <ol>
        <li>在客户名单下载模板，填写号码并上传，核对预览与错误结果。</li>
        <li>维护并发布业务知识，配置话术目标、采集字段和转人工策略。</li>
        <li>发布话术版本，创建外呼任务，选择名单批次并核对拨打策略。</li>
        <li>保存任务后另行启动。任务使用保存时的话术与知识快照。</li>
        <li>查看通话证据、复核质检结果，在线索与跟进中处理后续工作。</li>
      </ol>
      <h3>短信营销</h3>
      <p>
        先配置签名和模板，内部批准后供任务使用。真实发送还需通道接入和供应商审核，模拟状态会在页面标明。
      </p>
      <h3>系统配置</h3>
      <p>线路、模型凭据和通道由管理员维护；启用不等于已完成真实服务联调。</p>
    </template>
    <p v-else>
      在工作台设置接听状态，处理分配给您的通话和线索。通话证据、跟进与个人配置按账号权限显示。
    </p>
  </el-drawer>
</template>
<style scoped>
.workspace-pages {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.workspace-pages > span {
  display: inline-flex;
  gap: 2px;
}

.nav-toggle {
  display: block;
  border: 0;
  background: transparent;
  text-align: left;
  width: 100%;
  cursor: pointer;
  color: inherit;
  font: inherit;
  padding: 0;
}
.nav-toggle:after {
  content: '⌄';
  float: right;
}
.nav-toggle[aria-expanded='false']:after {
  content: '›';
}
</style>
