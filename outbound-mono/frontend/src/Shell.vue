<script setup>
import { computed, ref, watch, onMounted, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import SupportDesk from './components/SupportDesk.vue';
import ProductHelp from './components/ProductHelp.vue';
import NotificationPanel from './components/NotificationPanel.vue';
import WorkspaceTabs from './components/WorkspaceTabs.vue';
import SidebarNavigation from './components/SidebarNavigation.vue';
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
const narrowViewport = window.matchMedia('(max-width: 900px)');
const narrow = ref(narrowViewport.matches);
const drawerOpen = ref(false);
const drawerNavigation = ref(null);
const navigationLabel = computed(() =>
  t(narrow.value ? 'navigationMenu' : collapsed.value ? 'expandNavigation' : 'collapseNavigation'),
);
function syncViewport(event) {
  narrow.value = event.matches;
  if (!narrow.value) drawerOpen.value = false;
}
onMounted(() => narrowViewport.addEventListener('change', syncViewport));
onUnmounted(() => narrowViewport.removeEventListener('change', syncViewport));
function toggleNavigation() {
  if (narrow.value) {
    revealCurrentGroup();
    drawerOpen.value = true;
  } else collapsed.value = !collapsed.value;
}
const dial = useData(
  () => (['admin', 'operator'].includes(session.user?.role) ? '/api/v1/ops/dial-control' : ''),
  15000,
);
const switchingCalls = ref(false);
async function switchCalls() {
  const control = dial.data.value;
  if (!control || switchingCalls.value) return;
  switchingCalls.value = true;
  try {
    await action(
      () =>
        request('/api/v1/ops/dial-control', 'PUT', {
          enabled: !control.enabled,
          revision: control.revision,
        }),
      {
        confirm: control.enabled
          ? '停止本租户新派发？已获得资源的通话可继续。'
          : '恢复本租户新派发？',
        refresh: dial.load,
      },
    );
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') await dial.load();
  } finally {
    switchingCalls.value = false;
  }
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
const accountName = computed(() => session.user?.full_name || session.user?.username || '');
const accountInitial = computed(() => Array.from(accountName.value)[0]?.toUpperCase() || '·');
const serviceLabel = computed(() =>
  healthy.value === null ? t('checkingService') : t(healthy.value ? 'online' : 'offline'),
);
function accountAction(command) {
  if (command === 'logout') return leave();
  return router.push('/' + props.role + '/operations/workspace');
}
const agentPath = (p) => '/agent/operations/' + p;
const adminItems = (items) =>
  items.map(([path, label]) => ({ path: '/admin' + (path ? '/' + path : ''), label }));
const navigation = computed(() =>
  props.role === 'admin'
    ? adminMenu.map(({ group, items, sections }) => ({
        group,
        items: items ? adminItems(items) : [],
        sections: sections?.map((section) => ({
          group: section.group,
          items: adminItems(section.items),
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
function revealCurrentGroup() {
  for (const section of navigation.value) {
    for (const child of section.sections || [section]) {
      if (child.items.some((item) => item.path === route.path)) {
        groupOpen.value[section.group] = true;
        if (section.sections) groupOpen.value[section.group + ':' + child.group] = true;
      }
    }
  }
}
function toggleGroup(key) {
  groupOpen.value[key] = groupOpen.value[key] === false;
}
watch(() => [route.path, navigation.value], revealCurrentGroup, { immediate: true });
watch(
  () => route.fullPath,
  () => (drawerOpen.value = false),
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
  <el-container class="app-shell" :class="{ 'navigation-open': drawerOpen }">
    <el-aside v-if="!narrow" :width="collapsed ? '76px' : '232px'" class="sidebar">
      <SidebarNavigation
        :role="role"
        :groups="navigation"
        :group-open="groupOpen"
        :collapsed="collapsed"
        @toggle-group="toggleGroup"
      />
    </el-aside>
    <el-container
      ><el-header class="app-header">
        <div class="header-location">
          <button
            type="button"
            class="header-button"
            :aria-label="navigationLabel"
            :aria-expanded="narrow ? drawerOpen : !collapsed"
            :aria-haspopup="narrow ? 'dialog' : undefined"
            :title="navigationLabel"
            @click="toggleNavigation"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16" /></svg>
            <span v-if="narrow">{{ t('menuShort') }}</span>
          </button>
          <strong :title="t(route.meta.label || 'dashboard')">{{
            t(route.meta.label || 'dashboard')
          }}</strong>
        </div>
        <div class="header-runtime" :aria-label="t('runtimeStatus')" role="group">
          <template v-if="dial.data.value">
            <button
              type="button"
              class="dial-control"
              :class="{ 'is-on': dial.data.value.enabled }"
              :disabled="session.user?.role !== 'admin' || switchingCalls"
              :aria-pressed="dial.data.value.enabled"
              @click="switchCalls"
            >
              <span class="status-dot"></span>{{ t('dialControl') }}
              <b>{{ t(dial.data.value.enabled ? 'stateOn' : 'stateOff') }}</b>
            </button>
            <span class="runtime-metric" :title="dial.data.value.updated_at">
              {{ t('liveConcurrency') }} <b>{{ dial.data.value.active }}</b>
            </span>
            <span v-if="dial.data.value.voice_balance" class="runtime-metric">
              {{
                t(
                  dial.data.value.voice_balance.mode === 'simulation'
                    ? 'simulatedBalance'
                    : 'voiceBalance',
                )
              }}
              <b>{{
                dial.data.value.voice_balance.available
                  ? dial.data.value.voice_balance.amount + ' ' + t('yuanUnit')
                  : t('balanceUnavailable')
              }}</b>
            </span>
          </template>
          <span v-else-if="dial.error.value" class="runtime-metric">{{
            t('dialStatusUnavailable')
          }}</span>
          <span
            class="service-status"
            :class="{ 'is-healthy': healthy === true, 'is-unhealthy': healthy === false }"
            role="status"
          >
            <span class="status-dot"></span>{{ serviceLabel }}
          </span>
        </div>
        <div class="header-tools" :aria-label="t('headerTools')" role="group">
          <el-dropdown trigger="click" @command="setLanguage">
            <button
              type="button"
              class="header-button language-button"
              :aria-label="t('language')"
              :title="t('language')"
            >
              {{ language === 'zh-CN' ? '中' : 'EN' }}
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="zh-CN" :disabled="language === 'zh-CN'"
                  >中文</el-dropdown-item
                >
                <el-dropdown-item command="en-US" :disabled="language === 'en-US'"
                  >English</el-dropdown-item
                >
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <NotificationPanel :role="role" @read="unread.load">
            <el-badge
              :value="unread.data.value?.count"
              :hidden="!unread.data.value?.count"
              :max="99"
            >
              <button
                type="button"
                class="header-button"
                :aria-label="t('notificationCenter')"
                :title="t('notificationCenter')"
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M6 9a6 6 0 0 1 12 0v5l2 3H4l2-3zm4 11a2 2 0 0 0 4 0" />
                </svg>
              </button>
            </el-badge>
          </NotificationPanel>
          <button
            type="button"
            class="header-button"
            :aria-label="t('supportDesk')"
            :title="t('supportDesk')"
            @click="supportOpen = true"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M4 13v-2a8 8 0 0 1 16 0v5a4 4 0 0 1-4 4h-4M4 12h3v5H4zm13 0h3v5h-3z" />
            </svg>
          </button>
          <button
            type="button"
            class="header-button"
            :aria-label="t('help')"
            :title="t('help')"
            @click="helpOpen = true"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="12" cy="12" r="9" />
              <path d="M9.5 9a2.5 2.5 0 1 1 3.7 2.2c-.8.5-1.2 1-1.2 1.8m0 3h.01" />
            </svg>
          </button>
          <el-dropdown trigger="click" @command="accountAction">
            <button
              type="button"
              class="header-button account-button"
              :aria-label="t('accountMenu')"
              :title="accountName"
            >
              <span class="account-avatar" aria-hidden="true">{{ accountInitial }}</span>
              <span class="account-name">{{ accountName }}</span>
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m7 10 5 5 5-5" /></svg>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="workspace">{{
                  t('personalConfiguration')
                }}</el-dropdown-item>
                <el-dropdown-item command="logout" divided>{{ t('logout') }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main>
        <WorkspaceTabs
          :pages="openPages"
          :active-path="route.path"
          @select="(item) => router.push(item.url)"
          @close="closePage"
          @refresh="refreshIndex++" />
        <router-view
          :key="route.path + ':' + session.epoch + ':' + refreshIndex" /></el-main></el-container
  ></el-container>
  <el-drawer
    v-model="drawerOpen"
    class="navigation-drawer"
    :title="t('navigationMenu')"
    direction="ltr"
    size="min(320px, 100vw)"
    append-to-body
    :lock-scroll="false"
    destroy-on-close
    @opened="drawerNavigation?.scrollToCurrent()"
  >
    <SidebarNavigation
      ref="drawerNavigation"
      :role="role"
      :groups="navigation"
      :group-open="groupOpen"
      searchable
      @toggle-group="toggleGroup"
      @select-current="drawerOpen = false"
    />
  </el-drawer>
  <SupportDesk v-if="supportOpen" @close="supportOpen = false" /><el-drawer
    v-if="helpOpen"
    v-model="helpOpen"
    title="操作帮助"
    size="min(420px, 100vw)"
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
    <ProductHelp />
  </el-drawer>
</template>
<style scoped>
.app-header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 12px;
  height: var(--outbound-header-height);
  padding-inline: 16px;
}
.header-location,
.header-tools,
.header-runtime {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.header-location strong,
.account-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.header-location {
  gap: 10px;
}
.header-runtime {
  gap: 10px;
  padding: 4px 8px;
  background: #f5f7fb;
  border: 1px solid #e5eaf3;
  border-radius: 8px;
  color: #4b5871;
  font-size: 12px;
}
.header-runtime b {
  color: #18253e;
  font-variant-numeric: tabular-nums;
}
.dial-control,
.service-status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 0;
  border-radius: 5px;
  padding: 5px 7px;
  background: #edf0f6;
  color: #4b5871;
  font: inherit;
}
.dial-control:not(:disabled) {
  cursor: pointer;
}
.dial-control:not(:disabled):hover {
  box-shadow: inset 0 0 0 1px currentColor;
}
.dial-control.is-on,
.service-status.is-healthy {
  color: #126344;
  background: #e6f4ed;
}
.service-status.is-unhealthy {
  color: #a61c32;
  background: #fcecef;
}
.status-dot {
  width: 7px;
  height: 7px;
  flex: 0 0 7px;
  background: currentColor;
  border-radius: 50%;
}
.header-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  flex: 0 0 auto;
  min-width: 34px;
  height: 34px;
  padding: 0 6px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #40506c;
  font: inherit;
  cursor: pointer;
}
.header-button:hover {
  background: #eef3fb;
  color: #2457d6;
}
.header-button svg {
  width: 19px;
  height: 19px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.language-button {
  font-size: 13px;
  font-weight: 600;
}
.account-button {
  margin-left: 4px;
}
.account-button svg {
  width: 14px;
}
.account-avatar {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  color: #234eae;
  background: #e8efff;
  font-size: 13px;
  font-weight: 700;
}
.account-name {
  max-width: 116px;
  font-size: 13px;
}
@media (max-width: 1120px) {
  .app-header {
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-rows: 34px auto;
    gap: 6px;
    padding: 7px 10px;
  }
  .header-runtime {
    grid-row: 2;
    grid-column: 1 / -1;
    flex-wrap: wrap;
    gap: 4px 9px;
  }
}
@media (max-width: 700px) {
  .app-header {
    padding-inline: 8px;
  }
  .header-tools {
    gap: 3px;
  }
  .account-name {
    display: none;
  }
}
</style>
