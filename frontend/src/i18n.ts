import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'

const zh = {
  product: 'AI 外呼平台', adminPortal: '管理中心', agentPortal: '座席中心',
  dashboard: '仪表盘', contacts: '客户管理', scripts: '话术管理', campaigns: '外呼任务', calls: '通话记录',
  users: '用户与座席', lines: '外呼线路', settings: '系统配置', system: '监控与审计',
  workspace: '座席工作台', apiDocs: 'API 文档', logout: '退出登录', language: '语言', online: '服务正常', offline: '服务异常',
  welcomeBack: '欢迎回来', loginHint: '登录后进入安全工作台', username: '用户名', password: '密码', signIn: '登录',
  adminDemo: '管理员演示账号：admin / 12345678', agentDemo: '座席演示账号：1001@test / 12345678',
  overview: '运营总览', overviewHint: '查看当前租户的客户、任务和通话运行情况', totalContacts: '客户总数', activeScripts: '启用话术', totalCampaigns: '外呼任务', totalCalls: '累计通话',
  recentCalls: '最近通话', quickActions: '快捷操作', createCampaign: '创建任务', addContact: '新增客户', createScript: '新建话术', startCall: '发起外呼',
  search: '搜索', reset: '重置', create: '新建', edit: '编辑', save: '保存', cancel: '取消', delete: '删除', actions: '操作', refresh: '刷新', details: '详情',
  phone: '手机号', name: '名称', contactName: '姓名', tags: '标签', consent: '同意状态', dnc: '禁打', timezone: '时区', createdAt: '创建时间', yes: '是', no: '否',
  contactHint: '集中管理客户、同意状态与禁打名单', scriptHint: '配置可复用的 AI 外呼话术', campaignHint: '组合客户、话术和拨打策略', callHint: '查询通话状态、事件与人工接管',
  category: '分类', version: '版本', enabled: '启用', content: '话术内容', description: '说明',
  mode: '模式', concurrency: '并发数', retryLimit: '重试次数', retryInterval: '重试间隔（秒）', contactsSelected: '选择客户', scriptTemplate: '话术模板', recording: '通话录音', hangupSms: '挂机短信', status: '状态', start: '启动', pause: '暂停', resume: '恢复', stop: '停止', stopped: '已停止', paused: '已暂停', editCampaign: '编辑任务', confirmStop: '确认停止该外呼任务？', invalidPhone: '请输入 6 至 15 位有效号码',
  attempts: '尝试次数', campaignId: '任务 ID', contactId: '客户 ID', handoff: '转人工', hangup: '挂断', retry: '重拨', events: '事件',
  callNow: '立即呼叫', workbenchHint: '发起单路外呼并处理需要人工介入的会话', activeQueue: '当前会话', agentStatus: '座席状态', ready: '空闲',
  operationSuccess: '操作成功', loadFailed: '加载失败', loginFailed: '登录失败', empty: '暂无数据', confirmDelete: '确认删除这条记录？', confirmStart: '确认启动该外呼任务？', confirmHandoff: '确认将该通话转接人工？', confirmHangup: '确认挂断该通话？', confirmRetry: '确认重新拨打该通话？',
  all: '全部', unknown: '未知', consented: '已同意', notConsented: '未同意', revoked: '已撤回', draft: '草稿', running: '运行中', completed: '已完成', failed: '失败', deleted: '已删除',
  created: '已创建', queued: '排队中', dialing: '拨号中', answered: '已接听', in_ai: 'AI 通话中', waiting_human: '等待人工', handoff_transferring: '转接中', no_answer: '无人接听', busy: '忙线', voicemail: '语音信箱',
  humanOnly: '纯人工', aiOnly: '纯 AI', aiHandoff: 'AI 转人工', aiWithSms: 'AI + 短信', mixedHumanFirst: '人工优先',
  systemStatus: '系统状态', tenant: '租户', role: '角色', profile: '当前账号', noPermission: '无权访问该页面', backHome: '返回首页',
  usersHint: '管理管理员、座席账号、角色和启停状态', addUser: '新增用户', editUser: '编辑用户', newPasswordOptional: '新密码（不修改请留空）', supervisor: '班组长', administrator: '管理员', agent: '座席',
  linesHint: '配置运营商、SIP 网关、主叫号码和线路并发', addLine: '新增线路', editLine: '编辑线路', provider: '服务商', gatewayUrl: '网关地址', callerId: '主叫号码', aliyun: '阿里云', mockTestOnly: 'Mock（仅测试）', credentialRef: '凭证引用', credentialRefHint: '填写后从 TELEPHONY_SECRET_<引用> 环境变量读取，页面不保存密钥。', linePriority: '优先级', lineWeight: '权重',
  settingsHint: '集中配置并发容量、AI、语音、短信、合规和业务回调', settingsSecurityHint: '敏感密钥不在页面中保存；生产凭证应通过环境变量或密钥管理服务注入。', capacitySettings: '并发容量', aiVoice: 'AI 与语音', smsSettings: '短信配置', compliance: '合规策略', integrations: '接口与回调',
  tenantCallCapacity: '租户最大同时通话数', tenantCallCapacityHint: '保存后立即生效，无需重启服务；活动和线路仍可设置更低上限。', capacityChangeWarning: '提高并发前请先确认线路、PBX、ASR 和 TTS 额度', capacityChangeDescription: '系统会立即按新上限调度，但不会自动购买云厂商并发或扩容服务器。',
  effectiveCapacity: '实际生效并发', configuredCapacity: '已配置容量', lineCapacity: '线路容量', activeCalls: '当前活跃通话', availableSlots: '可用槽位', limitingSource: '限制来源', notLimited: '未参与限制', tenant_capacity: '租户容量', telephony_line: '外呼线路', tenant_and_line: '租户与线路', capacityOverview: '并发容量概览', campaignConcurrencyHint: '不能超过当前实际生效容量：{{count}} 路', capacityLoading: '正在读取系统容量，读取完成后可填写', attemptInterval: '无人接听/忙线重拨间隔',
  aiEnabled: '启用 AI 服务', agentUrl: 'AI 服务地址', llmProvider: '大模型服务商', llmModel: '模型名称', asrProvider: '语音识别服务', ttsProvider: '语音合成服务', voice: '音色', smsEnabled: '启用短信', senderId: '短信签名', endpoint: '服务地址', hangupTemplate: '挂机短信模板',
  dncEnforced: '强制禁呼检查', requireExplicitConsent: '活动必须明确授权', recordingNotice: '播放录音告知', maxAttemptsDay: '每日最大尝试次数', startHour: '允许开始小时', endHour: '允许结束小时', callbackEnabled: '启用业务回调', webhookBaseUrl: 'Webhook 地址', webhookTimeout: '回调超时', webhookSecretRef: '签名密钥引用', webhookSecretHint: '从 BUSINESS_WEBHOOK_SECRET_<引用> 环境变量读取，页面不保存密钥。', webhookRetryTimes: '回调重试次数', webhookRetryBackoff: '重试退避', seconds: '秒', lastUpdated: '最后更新',
  systemHint: '查看依赖服务、资源状态、通话分布和管理员操作记录', serviceHealth: '服务健康状态', database: '数据库', aiService: 'AI 服务', telephony: '外呼线路', enabledUsers: '启用用户', enabledLines: '启用线路', callStatusDistribution: '通话状态分布', auditLogs: '审计日志', smsLogs: '短信发送记录', sentAt: '发送时间', operator: '操作人', action: '动作', resource: '资源类型', resourceId: '资源 ID',
}

const en: typeof zh = {
  product: 'AI Outbound Platform', adminPortal: 'Admin Center', agentPortal: 'Agent Center',
  dashboard: 'Dashboard', contacts: 'Contacts', scripts: 'Scripts', campaigns: 'Campaigns', calls: 'Call History',
  users: 'Users & Agents', lines: 'Telephony Lines', settings: 'System Settings', system: 'Monitoring & Audit',
  workspace: 'Agent Workspace', apiDocs: 'API Docs', logout: 'Sign out', language: 'Language', online: 'Service healthy', offline: 'Service unavailable',
  welcomeBack: 'Welcome back', loginHint: 'Sign in to your secure workspace', username: 'Username', password: 'Password', signIn: 'Sign in',
  adminDemo: 'Admin demo: admin / 12345678', agentDemo: 'Agent demo: 1001@test / 12345678',
  overview: 'Operations Overview', overviewHint: 'Review contacts, campaigns and calls for the current tenant', totalContacts: 'Contacts', activeScripts: 'Active scripts', totalCampaigns: 'Campaigns', totalCalls: 'Total calls',
  recentCalls: 'Recent calls', quickActions: 'Quick actions', createCampaign: 'Create campaign', addContact: 'Add contact', createScript: 'Create script', startCall: 'Start call',
  search: 'Search', reset: 'Reset', create: 'Create', edit: 'Edit', save: 'Save', cancel: 'Cancel', delete: 'Delete', actions: 'Actions', refresh: 'Refresh', details: 'Details',
  phone: 'Phone', name: 'Name', contactName: 'Contact name', tags: 'Tags', consent: 'Consent', dnc: 'Do not call', timezone: 'Timezone', createdAt: 'Created at', yes: 'Yes', no: 'No',
  contactHint: 'Manage contacts, consent and do-not-call status', scriptHint: 'Configure reusable AI outbound scripts', campaignHint: 'Combine contacts, scripts and dialing policies', callHint: 'Review calls, events and human handoffs',
  category: 'Category', version: 'Version', enabled: 'Enabled', content: 'Script content', description: 'Description',
  mode: 'Mode', concurrency: 'Concurrency', retryLimit: 'Retry limit', retryInterval: 'Retry interval (sec)', contactsSelected: 'Contacts', scriptTemplate: 'Script template', recording: 'Recording', hangupSms: 'Hangup SMS', status: 'Status', start: 'Start', pause: 'Pause', resume: 'Resume', stop: 'Stop', stopped: 'Stopped', paused: 'Paused', editCampaign: 'Edit campaign', confirmStop: 'Stop this campaign?', invalidPhone: 'Enter a valid phone number with 6 to 15 digits',
  attempts: 'Attempts', campaignId: 'Campaign ID', contactId: 'Contact ID', handoff: 'Handoff', hangup: 'Hang up', retry: 'Retry', events: 'Events',
  callNow: 'Call now', workbenchHint: 'Start outbound calls and handle sessions that need an agent', activeQueue: 'Active sessions', agentStatus: 'Agent status', ready: 'Ready',
  operationSuccess: 'Operation completed', loadFailed: 'Failed to load', loginFailed: 'Sign-in failed', empty: 'No data', confirmDelete: 'Delete this record?', confirmStart: 'Start this campaign?', confirmHandoff: 'Transfer this call to a human agent?', confirmHangup: 'Hang up this call?', confirmRetry: 'Retry this call?',
  all: 'All', unknown: 'Unknown', consented: 'Consented', notConsented: 'Not consented', revoked: 'Revoked', draft: 'Draft', running: 'Running', completed: 'Completed', failed: 'Failed', deleted: 'Deleted',
  created: 'Created', queued: 'Queued', dialing: 'Dialing', answered: 'Answered', in_ai: 'In AI call', waiting_human: 'Waiting for agent', handoff_transferring: 'Transferring', no_answer: 'No answer', busy: 'Busy', voicemail: 'Voicemail',
  humanOnly: 'Human only', aiOnly: 'AI only', aiHandoff: 'AI handoff', aiWithSms: 'AI + SMS', mixedHumanFirst: 'Human first',
  systemStatus: 'System status', tenant: 'Tenant', role: 'Role', profile: 'Account', noPermission: 'You do not have access to this page', backHome: 'Back to home',
  usersHint: 'Manage administrators, agents, roles and account status', addUser: 'Add user', editUser: 'Edit user', newPasswordOptional: 'New password (leave blank to keep)', supervisor: 'Supervisor', administrator: 'Administrator', agent: 'Agent',
  linesHint: 'Configure carriers, SIP gateways, caller IDs and line concurrency', addLine: 'Add line', editLine: 'Edit line', provider: 'Provider', gatewayUrl: 'Gateway URL', callerId: 'Caller ID', aliyun: 'Alibaba Cloud', mockTestOnly: 'Mock (testing only)', credentialRef: 'Credential reference', credentialRefHint: 'Reads TELEPHONY_SECRET_<REFERENCE> from the environment. Secrets are never stored in the UI.', linePriority: 'Priority', lineWeight: 'Weight',
  settingsHint: 'Configure concurrency capacity, AI, voice, SMS, compliance and business callbacks', settingsSecurityHint: 'Secrets are not stored on this page. Inject production credentials through environment variables or a secrets manager.', capacitySettings: 'Concurrency Capacity', aiVoice: 'AI & Voice', smsSettings: 'SMS Settings', compliance: 'Compliance', integrations: 'Integrations',
  tenantCallCapacity: 'Maximum concurrent calls for tenant', tenantCallCapacityHint: 'Takes effect immediately without a restart. Campaign and line limits can still impose a lower cap.', capacityChangeWarning: 'Confirm telephony, PBX, ASR and TTS quotas before raising capacity', capacityChangeDescription: 'The scheduler uses the new limit immediately, but this does not purchase provider quota or scale servers automatically.',
  effectiveCapacity: 'Effective capacity', configuredCapacity: 'Configured capacity', lineCapacity: 'Line capacity', activeCalls: 'Active calls', availableSlots: 'Available slots', limitingSource: 'Limiting source', notLimited: 'Not limiting', tenant_capacity: 'Tenant capacity', telephony_line: 'Telephony line', tenant_and_line: 'Tenant and line', capacityOverview: 'Concurrency overview', campaignConcurrencyHint: 'Cannot exceed the current effective capacity: {{count}} calls', capacityLoading: 'Loading system capacity before editing', attemptInterval: 'No-answer/busy retry interval',
  aiEnabled: 'Enable AI service', agentUrl: 'AI service URL', llmProvider: 'LLM provider', llmModel: 'Model', asrProvider: 'ASR provider', ttsProvider: 'TTS provider', voice: 'Voice', smsEnabled: 'Enable SMS', senderId: 'Sender ID', endpoint: 'Endpoint', hangupTemplate: 'Hangup SMS template',
  dncEnforced: 'Enforce DNC checks', requireExplicitConsent: 'Require explicit campaign consent', recordingNotice: 'Play recording notice', maxAttemptsDay: 'Max attempts per day', startHour: 'Allowed start hour', endHour: 'Allowed end hour', callbackEnabled: 'Enable callback', webhookBaseUrl: 'Webhook URL', webhookTimeout: 'Webhook timeout', webhookSecretRef: 'Signing secret reference', webhookSecretHint: 'Reads BUSINESS_WEBHOOK_SECRET_<REFERENCE> from the environment. Secrets are never stored in the UI.', webhookRetryTimes: 'Callback retries', webhookRetryBackoff: 'Retry backoff', seconds: 'sec', lastUpdated: 'Last updated',
  systemHint: 'Review service dependencies, resources, call distribution and administrator activity', serviceHealth: 'Service health', database: 'Database', aiService: 'AI service', telephony: 'Telephony', enabledUsers: 'Enabled users', enabledLines: 'Enabled lines', callStatusDistribution: 'Call status distribution', auditLogs: 'Audit logs', smsLogs: 'SMS delivery logs', sentAt: 'Sent at', operator: 'Operator', action: 'Action', resource: 'Resource', resourceId: 'Resource ID',
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
