import { createRouter, createWebHistory } from 'vue-router';
import { initialize, session } from './session';
import { initializeClient, clientSession } from './client/session';
const page = (name) => () => import(`./pages/${name}.vue`);
export const adminMenu = [
  {
    group: 'AI 外呼',
    items: [
      ['', '外呼工作台', 'Dashboard'],
      ['scripts', '话术管理', 'Scripts'],
      ['operations/shared-scripts', '收到的话术', 'SharedScripts'],
      ['knowledge', '知识库', 'Knowledge'],
      ['campaigns', '外呼任务', 'Campaigns'],
      ['calls', '通话记录', 'CallsWorkspace'],
      ['quality', '质检复核', 'Quality'],
      ['operations/leads', '线索与跟进', 'OperationsLeads'],
      ['operations/wecom', '企微添加', 'WecomTracking'],
      ['operations/overview', '数据统计', 'DailyAnalytics'],
      ['operations/performance', '快捷面板', 'CallPerformance'],
      ['reports', '外呼报表', 'ReportsWorkspace'],
    ],
  },
  {
    group: '短信营销',
    items: [
      ['sms/overview', '短信概览', 'Messaging', 'overview'],
      ['sms/content', '签名与模板', 'SmsAssets', 'content'],
      ['sms/tasks', '发送任务', 'SmsTasks'],
      ['sms/records', '发送记录', 'Messaging', 'records'],
      ['sms/analytics', '效果分析', 'Messaging', 'interactions'],
    ],
  },
  {
    group: '营销中台',
    items: [
      ['contacts', '客户名单', 'ContactsWorkspace'],
      ['marketing/datasets', '数据集与撞库', 'MarketingDatasets'],
      ['marketing/segments', '人群圈选', 'MarketingSegments'],
      ['marketing/journeys', '活动编排', 'MarketingJourneys'],
      ['marketing/outcomes', '触达结果规则', 'MarketingOutcomes'],
      ['marketing/profiles', '客户触达档案', 'MarketingProfiles'],
      ['marketing/links', '短链与域名', 'MarketingLinks'],
      ['marketing/dashboard', '营销漏斗', 'MarketingDashboard'],
    ],
  },
  {
    group: '系统',
    items: [
      ['users', '账号与角色', 'Users'],
      ['operations/teams', '团队与坐席', 'OperationsTeams'],
      ['clients', '企业租户', 'Clients'],
      ['lines', '线路与号码', 'Lines'],
      ['system/voice-ai', '模型与语音服务', 'VoiceAi'],
      ['system/messaging', '短信通道与接入', 'Messaging', 'developers'],
      ['billing', '用量与费用', 'Billing'],
      ['settings', '平台配置', 'Settings'],
      ['operations/configs', '表单标签与连接', 'OperationsConfigs'],
      ['system', '监控与审计', 'System'],
    ],
  },
];
export const adminNavigation = adminMenu.flatMap((section) => section.items);
const hiddenAdmin = [
  ['contacts-operations', 'ContactOperations'],
  ['operations/calls', 'OperationsCalls'],
  ['operations/reports', 'OperationsReports'],
  ['group-monitor', 'Groups'],
  ['product', 'Product'],
  ['business-debug', 'BusinessDebug'],
  ['bailian-trial', 'BailianTrial'],
  ['messaging', 'Messaging'],
  ['operations/notices', 'OperationsNotices'],
  ['operations/workspace', 'OperationsWorkspace'],
];
export const operatorNavigation = [
  ['contacts', '客户名单', 'ContactsWorkspace'],
  ['scripts', '话术管理', 'Scripts'],
  ['campaigns', '外呼任务', 'Campaigns'],
];
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/admin' },
    ...['admin', 'agent'].map((role) => ({
      path: `/${role}/login`,
      component: page('Login'),
      props: { role },
      meta: { public: true },
    })),
    {
      path: '/admin',
      component: () => import('./Shell.vue'),
      props: { role: 'admin' },
      meta: { role: 'admin' },
      children: [
        ...adminNavigation.map(([path, label, name, messagingSection]) => ({
          path,
          component: page(name),
          props: { role: 'admin' },
          meta: { label, messagingSection },
        })),
        ...hiddenAdmin.map(([path, name]) => ({
          path,
          component: page(name),
          props: { role: 'admin' },
          meta: { label: '业务工作区' },
        })),
        {
          path: 'voice-ai',
          redirect: (to) => ({ path: '/admin/system/voice-ai', query: to.query }),
        },
      ],
    },
    {
      path: '/agent',
      component: () => import('./Shell.vue'),
      props: { role: 'agent' },
      meta: { role: 'agent' },
      children: [
        ...operatorNavigation.map(([path, label, name]) => ({
          path,
          component: page(name),
          meta: { label, operatorOnly: true },
        })),
        ...[
          ['operations/shared-scripts', '收到的话术', 'SharedScripts'],
          ['operations/wecom', '企微添加', 'WecomTracking'],
          ['operations/calls', '话单查询', 'OperationsCalls'],
          ['operations/leads', '线索与跟进', 'OperationsLeads'],
          ['operations/overview', '数据统计', 'DailyAnalytics'],
          ['operations/performance', '快捷面板', 'CallPerformance'],
          ['operations/reports', '运营分析', 'OperationsReports'],
          ['operations/notices', '通知中心', 'OperationsNotices'],
          ['operations/workspace', '坐席个人配置', 'OperationsWorkspace'],
        ].map(([path, label, name]) => ({ path, component: page(name), meta: { label } })),
        { path: '', component: page('Agent'), meta: { label: 'workspace' } },
        {
          path: 'calls',
          component: page('Calls'),
          props: { role: 'agent' },
          meta: { label: 'calls' },
        },
        { path: 'quality', component: page('Quality'), meta: { label: 'qualityReview' } },
      ],
    },
    {
      path: '/client/login',
      component: () => import('./client/ClientLogin.vue'),
      meta: { client: true, public: true },
    },
    {
      path: '/client',
      component: () => import('./client/ClientShell.vue'),
      meta: { client: true },
      children: [
        { path: '', component: () => import('./client/ClientLeads.vue'), meta: { label: '线索' } },
        {
          path: 'stats',
          component: () => import('./client/ClientStats.vue'),
          meta: { label: '数据看板' },
        },
        {
          path: 'members',
          component: () => import('./client/ClientMembers.vue'),
          meta: { label: '成员管理' },
        },
        {
          path: 'password',
          component: () => import('./client/ClientPassword.vue'),
          meta: { label: '修改密码' },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', component: page('NotFound'), meta: { public: true } },
  ],
});
router.beforeEach(async (to) => {
  // The client portal has its own principal and never consults the staff session.
  if (to.matched.some((r) => r.meta.client)) {
    await initializeClient();
    if (to.meta.public) return clientSession.user ? '/client' : true;
    if (!clientSession.user) return '/client/login';
    if (clientSession.user.must_change_password && to.path !== '/client/password')
      return '/client/password';
    return true;
  }
  await initialize();
  if (to.meta.public) {
    if (to.path.endsWith('/login') && session.user)
      return to.path.startsWith('/admin') && session.user.role === 'admin' ? '/admin' : '/agent';
    return true;
  }
  if (!session.user) return '/' + (to.meta.role || 'admin') + '/login';
  if (session.user.role === 'operator' && to.path === '/agent') return '/agent/campaigns';
  if (to.meta.operatorOnly && session.user.role !== 'operator') return '/agent';
  if (to.meta.role === 'admin' && session.user.role !== 'admin') return '/agent';
  return true;
});
