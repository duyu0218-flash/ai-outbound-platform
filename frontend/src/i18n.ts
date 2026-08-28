import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'

const zh = {
  product: 'AI 外呼平台', adminPortal: '管理中心', agentPortal: '座席中心',
  dashboard: '仪表盘', contacts: '客户管理', scripts: '话术管理', campaigns: '外呼任务', calls: '通话记录',
  workspace: '座席工作台', apiDocs: 'API 文档', logout: '退出登录', language: '语言', online: '服务正常', offline: '服务异常',
  welcomeBack: '欢迎回来', loginHint: '登录后进入安全工作台', username: '用户名', password: '密码', signIn: '登录',
  adminDemo: '管理员演示账号：admin / 12345678', agentDemo: '座席演示账号：1001@test / 12345678',
  overview: '运营总览', overviewHint: '查看当前租户的客户、任务和通话运行情况', totalContacts: '客户总数', activeScripts: '启用话术', totalCampaigns: '外呼任务', totalCalls: '累计通话',
  recentCalls: '最近通话', quickActions: '快捷操作', createCampaign: '创建任务', addContact: '新增客户', createScript: '新建话术', startCall: '发起外呼',
  search: '搜索', reset: '重置', create: '新建', edit: '编辑', save: '保存', cancel: '取消', delete: '删除', actions: '操作', refresh: '刷新', details: '详情',
  phone: '手机号', name: '名称', contactName: '姓名', tags: '标签', consent: '同意状态', dnc: '禁打', timezone: '时区', createdAt: '创建时间', yes: '是', no: '否',
  contactHint: '集中管理客户、同意状态与禁打名单', scriptHint: '配置可复用的 AI 外呼话术', campaignHint: '组合客户、话术和拨打策略', callHint: '查询通话状态、事件与人工接管',
  category: '分类', version: '版本', enabled: '启用', content: '话术内容', description: '说明',
  mode: '模式', concurrency: '并发数', retryLimit: '重试次数', retryInterval: '重试间隔（秒）', contactsSelected: '选择客户', scriptTemplate: '话术模板', recording: '通话录音', hangupSms: '挂机短信', status: '状态', start: '启动',
  attempts: '尝试次数', campaignId: '任务 ID', contactId: '客户 ID', handoff: '转人工', hangup: '挂断', retry: '重拨', events: '事件',
  callNow: '立即呼叫', workbenchHint: '发起单路外呼并处理需要人工介入的会话', activeQueue: '当前会话', agentStatus: '座席状态', ready: '空闲',
  operationSuccess: '操作成功', loadFailed: '加载失败', loginFailed: '登录失败', empty: '暂无数据', confirmDelete: '确认删除这条记录？', confirmStart: '确认启动该外呼任务？',
  all: '全部', unknown: '未知', consented: '已同意', notConsented: '未同意', revoked: '已撤回', draft: '草稿', running: '运行中', completed: '已完成', failed: '失败', deleted: '已删除',
  created: '已创建', queued: '排队中', dialing: '拨号中', answered: '已接听', in_ai: 'AI 通话中', waiting_human: '等待人工', handoff_transferring: '转接中', no_answer: '无人接听', busy: '忙线', voicemail: '语音信箱',
  humanOnly: '纯人工', aiOnly: '纯 AI', aiHandoff: 'AI 转人工', aiWithSms: 'AI + 短信', mixedHumanFirst: '人工优先',
  systemStatus: '系统状态', tenant: '租户', role: '角色', profile: '当前账号', noPermission: '无权访问该页面', backHome: '返回首页',
}

const en: typeof zh = {
  product: 'AI Outbound Platform', adminPortal: 'Admin Center', agentPortal: 'Agent Center',
  dashboard: 'Dashboard', contacts: 'Contacts', scripts: 'Scripts', campaigns: 'Campaigns', calls: 'Call History',
  workspace: 'Agent Workspace', apiDocs: 'API Docs', logout: 'Sign out', language: 'Language', online: 'Service healthy', offline: 'Service unavailable',
  welcomeBack: 'Welcome back', loginHint: 'Sign in to your secure workspace', username: 'Username', password: 'Password', signIn: 'Sign in',
  adminDemo: 'Admin demo: admin / 12345678', agentDemo: 'Agent demo: 1001@test / 12345678',
  overview: 'Operations Overview', overviewHint: 'Review contacts, campaigns and calls for the current tenant', totalContacts: 'Contacts', activeScripts: 'Active scripts', totalCampaigns: 'Campaigns', totalCalls: 'Total calls',
  recentCalls: 'Recent calls', quickActions: 'Quick actions', createCampaign: 'Create campaign', addContact: 'Add contact', createScript: 'Create script', startCall: 'Start call',
  search: 'Search', reset: 'Reset', create: 'Create', edit: 'Edit', save: 'Save', cancel: 'Cancel', delete: 'Delete', actions: 'Actions', refresh: 'Refresh', details: 'Details',
  phone: 'Phone', name: 'Name', contactName: 'Contact name', tags: 'Tags', consent: 'Consent', dnc: 'Do not call', timezone: 'Timezone', createdAt: 'Created at', yes: 'Yes', no: 'No',
  contactHint: 'Manage contacts, consent and do-not-call status', scriptHint: 'Configure reusable AI outbound scripts', campaignHint: 'Combine contacts, scripts and dialing policies', callHint: 'Review calls, events and human handoffs',
  category: 'Category', version: 'Version', enabled: 'Enabled', content: 'Script content', description: 'Description',
  mode: 'Mode', concurrency: 'Concurrency', retryLimit: 'Retry limit', retryInterval: 'Retry interval (sec)', contactsSelected: 'Contacts', scriptTemplate: 'Script template', recording: 'Recording', hangupSms: 'Hangup SMS', status: 'Status', start: 'Start',
  attempts: 'Attempts', campaignId: 'Campaign ID', contactId: 'Contact ID', handoff: 'Handoff', hangup: 'Hang up', retry: 'Retry', events: 'Events',
  callNow: 'Call now', workbenchHint: 'Start outbound calls and handle sessions that need an agent', activeQueue: 'Active sessions', agentStatus: 'Agent status', ready: 'Ready',
  operationSuccess: 'Operation completed', loadFailed: 'Failed to load', loginFailed: 'Sign-in failed', empty: 'No data', confirmDelete: 'Delete this record?', confirmStart: 'Start this campaign?',
  all: 'All', unknown: 'Unknown', consented: 'Consented', notConsented: 'Not consented', revoked: 'Revoked', draft: 'Draft', running: 'Running', completed: 'Completed', failed: 'Failed', deleted: 'Deleted',
  created: 'Created', queued: 'Queued', dialing: 'Dialing', answered: 'Answered', in_ai: 'In AI call', waiting_human: 'Waiting for agent', handoff_transferring: 'Transferring', no_answer: 'No answer', busy: 'Busy', voicemail: 'Voicemail',
  humanOnly: 'Human only', aiOnly: 'AI only', aiHandoff: 'AI handoff', aiWithSms: 'AI + SMS', mixedHumanFirst: 'Human first',
  systemStatus: 'System status', tenant: 'Tenant', role: 'Role', profile: 'Account', noPermission: 'You do not have access to this page', backHome: 'Back to home',
}

const savedLanguage = localStorage.getItem('ai-platform-language') || 'zh-CN'

void i18n.use(initReactI18next).init({
  resources: { 'zh-CN': { translation: zh }, 'en-US': { translation: en } },
  lng: savedLanguage,
  fallbackLng: 'zh-CN',
  interpolation: { escapeValue: false },
})

export function setLanguage(language: 'zh-CN' | 'en-US') {
  localStorage.setItem('ai-platform-language', language)
  document.documentElement.lang = language
  void i18n.changeLanguage(language)
}

document.documentElement.lang = savedLanguage

export default i18n
