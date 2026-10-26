import { createRouter, createWebHistory } from 'vue-router';
import { initialize, session } from './session';
const page = (name) => () => import(`./pages/${name}.vue`);
export const adminNavigation = [
  ...[
    ['operations/calls', '话单查询', 'OperationsCalls'],
    ['operations/leads', '线索与跟进', 'OperationsLeads'],
    ['operations/reports', '运营分析', 'OperationsReports'],
    ['operations/teams', '团队与权限', 'OperationsTeams'],
    ['operations/configs', '表单标签与连接', 'OperationsConfigs'],
    ['operations/notices', '通知中心', 'OperationsNotices'],
    ['operations/workspace', '坐席个人配置', 'OperationsWorkspace'],
  ],
  ['', 'dashboard', 'Dashboard'],
  ['quality', 'qualityReview', 'Quality'],
  ['contacts', 'contacts', 'Contacts'],
  ['scripts', 'scripts', 'Scripts'],
  ['campaigns', 'campaigns', 'Campaigns'],
  ['calls', 'calls', 'Calls'],
  ['contacts-operations', 'contactsOperations', 'ContactOperations'],
  ['reports', 'reports', 'Reports'],
  ['group-monitor', 'groupMonitor', 'Groups'],
  ['billing', 'billing', 'Billing'],
  ['users', 'users', 'Users'],
  ['lines', 'lines', 'Lines'],
  ['knowledge', 'knowledge', 'Knowledge'],
  ['settings', 'settings', 'Settings'],
  ['voice-ai', '语音方案对比', 'VoiceAi'],
  ['product', '业务交付策略', 'Product'],
  ['system', 'system', 'System'],
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
    { path: '/:pathMatch(.*)*', component: page('NotFound'), meta: { public: true } },
  ],
});
router.beforeEach(async (to) => {
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
