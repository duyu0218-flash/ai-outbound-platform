import { createRouter, createWebHistory } from 'vue-router';
import { initialize, session } from './session';
import { initializeClient, clientSession } from './client/session';
const page = (name) => () => import(`./pages/${name}.vue`);
// Grouped by the business chain: lists -> scripts -> dialing -> QA/leads -> client delivery
// -> analysis, followed by organisation and platform administration.
export const adminMenu = [
  {
    group: 'navWorkbench',
    items: [
      ['', 'dashboard', 'Dashboard'],
      ['operations/notices', '通知中心', 'OperationsNotices'],
    ],
  },
  {
    group: 'navContacts',
    items: [
      ['contacts', 'contacts', 'Contacts'],
      ['contacts-operations', 'contactsOperations', 'ContactOperations'],
    ],
  },
  {
    group: 'navScripts',
    items: [
      ['scripts', 'scripts', 'Scripts'],
      ['knowledge', 'knowledge', 'Knowledge'],
      ['product', '业务交付策略', 'Product'],
      ['voice-ai', '语音方案对比', 'VoiceAi'],
      ['bailian-trial', '百炼业务试聊', 'BailianTrial'],
    ],
  },
  {
    group: 'navDialing',
    items: [
      ['campaigns', 'campaigns', 'Campaigns'],
      ['calls', 'calls', 'Calls'],
      ['operations/calls', '话单查询', 'OperationsCalls'],
      ['messaging', '消息营销', 'Messaging'],
    ],
  },
  {
    group: 'navQuality',
    items: [
      ['quality', 'qualityReview', 'Quality'],
      ['operations/leads', '线索与跟进', 'OperationsLeads'],
    ],
  },
  { group: 'navClients', items: [['clients', 'clientTenants', 'Clients']] },
  {
    group: 'navAnalysis',
    items: [
      ['reports', 'reports', 'Reports'],
      ['operations/reports', '运营分析', 'OperationsReports'],
      ['group-monitor', 'groupMonitor', 'Groups'],
      ['billing', 'billing', 'Billing'],
    ],
  },
  {
    group: 'navOrganization',
    items: [
      ['users', 'users', 'Users'],
      ['operations/teams', '团队与权限', 'OperationsTeams'],
      ['operations/workspace', '坐席个人配置', 'OperationsWorkspace'],
    ],
  },
  {
    group: 'navPlatform',
    items: [
      ['lines', 'lines', 'Lines'],
      ['settings', 'settings', 'Settings'],
      ['operations/configs', '表单标签与连接', 'OperationsConfigs'],
      ['system', 'system', 'System'],
    ],
  },
];
export const adminNavigation = adminMenu.flatMap((section) => section.items);
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
      children: adminNavigation.map(([path, label, name]) => ({
        path,
        component: page(name),
        props: { role: 'admin' },
        meta: { label },
      })),
    },
    {
      path: '/agent',
      component: () => import('./Shell.vue'),
      props: { role: 'agent' },
      meta: { role: 'agent' },
      children: [
        ...[
          ['operations/calls', '话单查询', 'OperationsCalls'],
          ['operations/leads', '线索与跟进', 'OperationsLeads'],
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
  if (to.meta.role === 'admin' && session.user.role !== 'admin') return '/agent';
  return true;
});
