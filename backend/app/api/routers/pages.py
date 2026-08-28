from fastapi import APIRouter, Depends
from fastapi.responses import HTMLResponse, RedirectResponse

from ...config import get_settings
from ...api.deps import require_role, require_roles

settings = get_settings()

router = APIRouter(tags=["pages"])




def _portal_page(
    page_title: str,
    default_username: str,
    default_password: str,
    default_role: str,
    dashboard_path: str,
    api_key: str,
    show_manage: bool,
) -> str:
    page = """\
<!doctype html>
<html lang=\"zh-CN\">
  <head>
    <meta charset=\"utf-8\" />
    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />
    <title>AI 外呼平台 · __TITLE__</title>
    <style>
      :root {
        --bg: #f8fafc;
        --panel: #ffffff;
        --line: #d5d9e2;
        --text: #111827;
        --muted: #6b7280;
        --primary: #2563eb;
        --primary-dark: #1d4ed8;
        --warn: #d97706;
        --danger: #b91c1c;
        --ok: #15803d;
        --radius: 10px;
      }
      * { box-sizing: border-box; }
      body {
        margin: 0;
        padding: 20px;
        background: var(--bg);
        color: var(--text);
        font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, \"PingFang SC\", \"Microsoft YaHei\", sans-serif;
      }
      .layout { max-width: 1400px; margin: 0 auto; }
      .topbar {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12px;
        margin-bottom: 12px;
      }
      h1, h2, h3 {
        margin: 0;
      }
      h1 { font-size: 24px; }
      h2 { font-size: 18px; margin-bottom: 10px; }
      .muted { color: var(--muted); font-size: 13px; }
      .card {
        background: var(--panel);
        border: 1px solid var(--line);
        border-radius: var(--radius);
        padding: 14px;
        margin-top: 12px;
      }
      .grid {
        display: grid;
        grid-template-columns: repeat(12, minmax(0, 1fr));
        gap: 12px;
      }
      .col-3 { grid-column: span 3; min-width: 0; }
      .col-4 { grid-column: span 4; min-width: 0; }
      .col-6 { grid-column: span 6; min-width: 0; }
      .col-8 { grid-column: span 8; min-width: 0; }
      .col-12 { grid-column: span 12; min-width: 0; }
      .row {
        display: flex;
        gap: 8px;
        align-items: center;
        margin: 8px 0;
        flex-wrap: wrap;
      }
      .row label {
        width: 110px;
        font-size: 12px;
        color: #374151;
      }
      .row input,
      .row select,
      .row textarea,
      .row button {
        font-size: 13px;
      }
      .row input,
      .row select,
      .row textarea {
        border: 1px solid #d1d5db;
        border-radius: 6px;
        padding: 8px 10px;
      }
      .row textarea { flex: 1; min-height: 72px; }
      input, select, textarea {
        min-width: 180px;
        flex: 1;
      }
      .btns {
        display: flex;
        gap: 8px;
        flex-wrap: wrap;
      }
      button {
        border: none;
        color: #fff;
        background: var(--primary);
        border-radius: 6px;
        padding: 8px 12px;
        cursor: pointer;
      }
      button:hover { background: var(--primary-dark); }
      button.secondary { background: #4b5563; }
      button.secondary:hover { background: #374151; }
      button.warn { background: var(--warn); }
      button.warn:hover { opacity: .9; }
      button.danger { background: var(--danger); }
      button.danger:hover { opacity: .9; }
      .status {
        margin-top: 8px;
        min-height: 20px;
        color: var(--ok);
        font-size: 13px;
      }
      .status.error {
        color: var(--danger);
      }
      .small {
        font-size: 12px;
        color: var(--muted);
      }
      pre {
        margin: 0;
        background: #0f172a;
        color: #d1e5ff;
        border-radius: 8px;
        padding: 10px;
        max-height: 320px;
        overflow: auto;
        white-space: pre-wrap;
      }
      .toolbar {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: 12px;
        margin-bottom: 8px;
      }
      table {
        width: 100%;
        border-collapse: collapse;
        font-size: 12px;
      }
      th, td {
        padding: 8px;
        border-bottom: 1px solid #e5e7eb;
        vertical-align: top;
        text-align: left;
      }
      th { background: #f1f5f9; }
      .inline {
        display: inline-flex;
        gap: 4px;
        flex-wrap: wrap;
      }
      .hidden { display: none; }
      .tag {
        display: inline-block;
        border: 1px solid var(--line);
        border-radius: 999px;
        padding: 2px 8px;
        margin-right: 4px;
        background: #f8fafc;
      }
      a {
        color: var(--primary);
        text-decoration: none;
      }
      a:hover { text-decoration: underline; }
      .language-switch {
        min-width: 118px;
        flex: 0 0 auto;
        border: 1px solid #d1d5db;
        border-radius: 6px;
        padding: 6px 8px;
        background: var(--panel);
      }
      @media (max-width: 1280px) {
        .col-3, .col-4, .col-6, .col-8, .col-12 { grid-column: span 12; }
      }
    </style>
  </head>
  <body>
    <div class="layout">
      <div class="topbar">
        <div>
          <h1><span data-i18n="appTitle">AI 外呼平台</span> · <span data-i18n="pageTitle">__TITLE__</span></h1>
          <div class="muted"><span data-i18n="demoAccount">默认演示账号</span>: __DEFAULT_USERNAME__ / __DEFAULT_PASSWORD__ (<span data-i18n="role">角色</span>: __DEFAULT_ROLE__)</div>
        </div>
        <div class="btns">
          <select id="languageSelect" class="language-switch" aria-label="Language" onchange="changeLanguage(this.value)">
            <option value="zh-CN">中文</option>
            <option value="en-US">English</option>
          </select>
          <a href="/admin" data-i18n="adminPortal">管理员端</a>
          <a href="/agent" data-i18n="agentPortal">座席端</a>
          <a href="/docs.html" data-i18n="apiDocs">API 文档</a>
          <a href="/health">/health</a>
          <a href="/healthz">/healthz</a>
        </div>
      </div>

      <div class="card">
        <div class="toolbar">
          <h2 data-i18n="accountConnectivity">账户与连通性</h2>
          <div class="small"><span data-i18n="rolePage">系统角色页</span>: __DASHBOARD_PATH__</div>
        </div>
        <div class="row"><label data-i18n="username">用户名</label><input id="username" value="__DEFAULT_USERNAME__" /></div>
        <div class="row"><label data-i18n="password">密码</label><input id="password" type="password" value="__DEFAULT_PASSWORD__" /></div>
        <div class="row"><label>API Key</label><input id="apiKey" value="__DEFAULT_API_KEY__" /></div>
        <div class="row"><label>Tenant ID</label><input id="tenantId" value="1" /></div>
        <div class="row"><label>Token</label><input id="tokenDisplay" readonly value="未登录" /></div>
        <div class="btns">
          <button onclick="login()" data-i18n="login">登录</button>
          <button class="secondary" onclick="checkMe()" data-i18n="checkMe">检查 /auth/me</button>
          <button class="secondary" onclick="checkDashboard()" data-i18n="checkDashboard">检查 dashboard</button>
          <button class="secondary" onclick="checkHealth()" data-i18n="checkHealth">检查服务健康</button>
          <button class="danger" onclick="logout()" data-i18n="logout">退出</button>
          <button class="secondary" onclick="clearLog()" data-i18n="clearLog">清日志</button>
        </div>
        <div class="row">
          <span id="authStatus" class="status" data-i18n="notLoggedIn">未登录</span>
          <span id="systemStatus" class="status"></span>
        </div>
      </div>

      <div class="grid">
        <section class="card col-4 __ADMIN_ONLY__">
          <div class="toolbar">
            <h2 data-i18n="contactManagement">联系人管理</h2>
            <div class="small" data-i18n="contactHelp">联系人可批量用于活动</div>
          </div>
          <div class="row"><label data-i18n="phone">手机号</label><input id="contactPhone" value="13800000000" /></div>
          <div class="row"><label data-i18n="name">姓名</label><input id="contactName" value="演示客户" /></div>
          <div class="row"><label data-i18n="tags">标签</label><input id="contactTags" value="demo" /></div>
          <div class="row"><label data-i18n="consentState">同意态</label>
            <select id="contactConsent">
              <option value="unknown">unknown</option>
              <option value="consented" selected>consented</option>
              <option value="not_consented">not_consented</option>
              <option value="revoked">revoked</option>
            </select>
          </div>
          <div class="row"><label data-i18n="dnc">禁打标识</label><input id="contactDnc" type="checkbox" /></div>
          <div class="btns">
            <button onclick="createContact()" data-i18n="add">新增</button>
            <button class="secondary" onclick="searchContacts()" data-i18n="search">查询</button>
          </div>
          <p id="contactStatus" class="status" data-i18n="notOperated">未操作</p>
          <div style="max-height: 240px; overflow:auto;">
            <table>
              <thead>
                <tr><th>ID</th><th data-i18n="phone">手机号</th><th data-i18n="name">姓名</th><th data-i18n="tags">标签</th><th data-i18n="consent">同意</th><th data-i18n="dncShort">禁打</th></tr>
              </thead>
              <tbody id="contactsTbody"></tbody>
            </table>
          </div>
          <div class="row">
            <label data-i18n="pagination">分页</label>
            <input id="contactsPage" type="number" value="1" min="1" style="max-width:96px;" />
            <input id="contactsSize" type="number" value="20" min="1" max="100" style="max-width:96px;" />
            <button class="secondary" onclick="searchContacts(true)" data-i18n="refresh">刷新</button>
          </div>
          <div id="contactStatus2" class="small"></div>
        </section>

        <section class="card col-8 __ADMIN_ONLY__">
          <div class="toolbar">
            <h2 data-i18n="scriptTemplates">话术模板</h2>
            <div class="small" data-i18n="templateHelp">支持创建模板后，在活动中复用</div>
          </div>
          <div class="row"><label data-i18n="templateName">模板名称</label><input id="templateName" value="欢迎开场话术" /></div>
          <div class="row"><label data-i18n="category">分类</label><input id="templateCategory" value="default" /></div>
          <div class="row"><label data-i18n="tags">标签</label><input id="templateTags" value="demo" /></div>
          <div class="row"><label data-i18n="enabled">是否启用</label><input id="templateActive" type="checkbox" checked /></div>
          <div class="row"><label data-i18n="content">内容</label><textarea id="templateContent">您好，{客户姓名}，我先为您确认一下订单信息。需要转人工请说“转人工”。</textarea></div>
          <div class="btns">
            <button onclick="createTemplate()" data-i18n="createTemplate">创建模板</button>
            <button class="secondary" onclick="searchTemplates(true)" data-i18n="templateList">模板列表</button>
          </div>
          <p id="templateStatus" class="status" data-i18n="notOperated">未操作</p>
          <div style="max-height: 240px; overflow:auto;">
            <table>
              <thead>
                <tr><th>ID</th><th data-i18n="name">名称</th><th data-i18n="category">分类</th><th data-i18n="enabled">是否启用</th><th data-i18n="actions">操作</th></tr>
              </thead>
              <tbody id="templatesTbody"></tbody>
            </table>
          </div>
          <div class="row">
            <label data-i18n="optional">选填</label>
            <button class="secondary" onclick="fillActiveTemplateToCampaign()" data-i18n="fillTemplate">将已选模板填入活动话术</button>
            <span id="templateHint" class="small" data-i18n="templateHint">新建活动可直接选择模板ID。</span>
          </div>
        </section>

        <section class="card col-12 __ADMIN_ONLY__">
          <div class="toolbar">
            <h2 data-i18n="campaignManagement">活动管理</h2>
            <div class="small" data-i18n="campaignHelp">绑定联系人与话术后可自动创建外呼任务</div>
          </div>
          <div class="row"><label data-i18n="campaignName">活动名称</label><input id="campaignName" value="演示活动" /></div>
          <div class="row"><label data-i18n="templateId">模板ID</label><input id="campaignTemplateId" placeholder="可选" data-i18n-placeholder="optional" /></div>
          <div class="row"><label data-i18n="scriptText">话术文本</label><textarea id="campaignScript">欢迎致电，先确认用户是否需要服务，再进行后续操作。</textarea></div>
          <div class="row">
            <label data-i18n="mode">模式</label>
            <select id="campaignMode">
              <option value="ai_handoff" selected>ai_handoff</option>
              <option value="ai_only">ai_only</option>
              <option value="human_only">human_only</option>
              <option value="ai_with_sms">ai_with_sms</option>
              <option value="mixed_human_first">mixed_human_first</option>
            </select>
            <label data-i18n="concurrency">并发</label><input id="campaignConcurrency" value="5" type="number" min="1" style="max-width:96px;" />
          </div>
          <div class="row">
            <label data-i18n="contactIds">联系人ID</label><input id="campaignContactIds" value="" placeholder="1,2,3" />
            <label>max_dials</label><input id="campaignMaxDials" value="1" type="number" min="1" style="max-width:96px;" />
            <label data-i18n="async">异步</label><input id="campaignAsyncDial" type="checkbox" checked />
          </div>
          <div class="row">
            <label data-i18n="startHint">启动提示</label><span class="small" data-i18n="asyncHint">异步模式下先返回排队结果，再到“外呼与会话”页刷新状态</span>
          </div>
          <div class="btns">
            <button onclick="createCampaign()" data-i18n="createCampaign">创建活动</button>
            <button class="secondary" onclick="searchCampaigns(true)" data-i18n="campaignList">活动列表</button>
          </div>
          <p id="campaignStatus" class="status" data-i18n="notOperated">未操作</p>
          <div style="max-height: 320px; overflow:auto;">
            <table>
              <thead>
                <tr><th>ID</th><th data-i18n="name">名称</th><th data-i18n="mode">模式</th><th data-i18n="contacts">联系人</th><th data-i18n="status">状态</th><th data-i18n="actions">操作</th></tr>
              </thead>
              <tbody id="campaignsTbody"></tbody>
            </table>
          </div>
        </section>

        <section class="card col-12">
          <div class="toolbar">
            <h2 data-i18n="callsSessions">外呼与会话</h2>
            <div class="small" data-i18n="callsHelp">支持单路发起外呼/启动活动回传状态</div>
          </div>
          <div class="row"><label data-i18n="callPhone">呼叫手机号</label><input id="callPhone" value="13800000000" /></div>
          <div class="row">
            <label data-i18n="mode">模式</label>
            <select id="callMode">
              <option value="ai_only">ai_only</option>
              <option value="ai_handoff" selected>ai_handoff</option>
              <option value="human_only">human_only</option>
              <option value="ai_with_sms">ai_with_sms</option>
              <option value="mixed_human_first">mixed_human_first</option>
            </select>
            <label>campaign_id</label><input id="callCampaignId" type="number" min="1" placeholder="可选" data-i18n-placeholder="optional" />
            <label>contact_id</label><input id="callContactId" type="number" min="1" placeholder="可选" data-i18n-placeholder="optional" />
            <label>max_attempts</label><input id="callMaxAttempts" value="1" type="number" min="1" style="max-width:90px;" />
          </div>
          <div class="btns">
            <button onclick="createCall()" data-i18n="startCall">发起外呼</button>
            <button class="secondary" onclick="searchCalls(true)" data-i18n="refreshCalls">刷新会话</button>
          </div>
          <p id="callStatus" class="status" data-i18n="notOperated">未操作</p>
          <div class="row">
            <label data-i18n="pagination">分页</label>
            <input id="callsPage" type="number" value="1" min="1" style="max-width:96px;" />
            <input id="callsSize" type="number" value="20" min="1" max="100" style="max-width:96px;" />
          </div>
          <div style="overflow:auto;">
            <table>
              <thead>
                <tr><th>ID</th><th data-i18n="phoneShort">电话</th><th data-i18n="mode">模式</th><th data-i18n="status">状态</th><th data-i18n="retry">重试</th><th>campaign_id</th><th>contact_id</th><th data-i18n="actions">操作</th></tr>
              </thead>
              <tbody id="callsTbody"></tbody>
            </table>
          </div>
        </section>

        <section class="card col-12">
          <div class="toolbar">
            <h2 data-i18n="systemLog">系统日志</h2>
            <div class="small" data-i18n="logHelp">所有关键操作会回显到此处</div>
          </div>
          <pre id="log">等待操作...</pre>
        </section>
      </div>
    </div>

    <script>
      const state = {
        token: '',
        storageKey: 'ai-platform-token-__DEFAULT_ROLE__',
        languageStorageKey: 'ai-platform-language',
        language: 'zh-CN',
        selectedTemplateId: '',
        campaignScriptFromTemplate: '',
        dashboardApi: '/api/v1/__DASHBOARD_PATH__',
        isAdmin: __IS_ADMIN__ === '1',
        page: { contactsPage:1, campaignsPage:1, callsPage:1 },
      };

      const translations = {
        'zh-CN': {
          appTitle: 'AI 外呼平台', adminPortal: '管理员端', agentPortal: '座席端',
          demoAccount: '默认演示账号', role: '角色', apiDocs: 'API 文档',
          accountConnectivity: '账户与连通性', rolePage: '系统角色页', username: '用户名', password: '密码',
          login: '登录', checkMe: '检查 /auth/me', checkDashboard: '检查 dashboard', checkHealth: '检查服务健康',
          logout: '退出', clearLog: '清日志', notLoggedIn: '未登录', notOperated: '未操作',
          contactManagement: '联系人管理', contactHelp: '联系人可批量用于活动', phone: '手机号', name: '姓名',
          tags: '标签', consentState: '同意态', dnc: '禁打标识', add: '新增', search: '查询', consent: '同意',
          dncShort: '禁打', pagination: '分页', refresh: '刷新', scriptTemplates: '话术模板',
          templateHelp: '支持创建模板后，在活动中复用', templateName: '模板名称', category: '分类',
          enabled: '是否启用', content: '内容', createTemplate: '创建模板', templateList: '模板列表', actions: '操作',
          optional: '可选', fillTemplate: '将已选模板填入活动话术', templateHint: '新建活动可直接选择模板ID。',
          campaignManagement: '活动管理', campaignHelp: '绑定联系人与话术后可自动创建外呼任务',
          campaignName: '活动名称', templateId: '模板ID', scriptText: '话术文本', mode: '模式', concurrency: '并发',
          contactIds: '联系人ID', async: '异步', startHint: '启动提示',
          asyncHint: '异步模式下先返回排队结果，再到“外呼与会话”页刷新状态', createCampaign: '创建活动',
          campaignList: '活动列表', contacts: '联系人', status: '状态', callsSessions: '外呼与会话',
          callsHelp: '支持单路发起外呼/启动活动回传状态', callPhone: '呼叫手机号', startCall: '发起外呼',
          refreshCalls: '刷新会话', phoneShort: '电话', retry: '重试', systemLog: '系统日志',
          logHelp: '所有关键操作会回显到此处', waiting: '等待操作...', logCleared: '日志已清空',
          loggedOut: '已退出', logoutLog: '退出登录', restoredSession: '已恢复本地会话', loggingIn: '登录中...',
          loginSuccess: '登录成功：{username}/{role}', loginFail: '登录失败：{error}',
          authOk: '鉴权通过：{username}（{role}）', authFail: '鉴权失败：{error}', dashboardOk: 'dashboard 可访问',
          dashboardDenied: 'dashboard 拒绝：{error}', healthOk: 'health ok: {status}', healthFail: 'health fail: {error}',
          contactCreated: '联系人创建成功：ID {id}', createFail: '创建失败：{error}', contactCount: '联系人共 {count} 条',
          pageSummary: '分页：第 {page} 页 / 每页 {size} 条', queryFail: '查询失败：{error}', yes: '是', no: '否',
          fillToCampaign: '填充到活动', offline: '下线', templateCount: '模板共 {count} 条', frontendCreated: '前端创建',
          templateCreated: '模板创建成功：ID {id}', templateOffline: '模板 {id} 已下线', updateFail: '更新失败：{error}',
          templateSelected: '已选择模板 {id}', templateReadFail: '模板读取失败：{error}', selectTemplate: '请先选择一个模板',
          campaignCreated: '活动创建成功：ID {id}', campaignCount: '活动共 {count} 条', start: '启动',
          startOne: '启动(1条)', resultCode: '，结果码 {code}', campaignStarted: '活动 {id} 启动，拨号{count}个{code}',
          startFail: '启动失败：{error}', callStarted: '外呼已发起：{id}', callFail: '外呼失败：{error}',
          callCount: '通话 {count} 条', handoff: '转人工', hangup: '挂断', retryAction: '重试', events: '事件',
          webhookDedup: 'Webhook去重', handoffOk: '转人工已触发', handoffFail: '转人工失败：',
          hangupOk: '挂断已提交', hangupFail: '挂断失败：', retryOk: '重试已提交', retryFail: '重试失败：',
          dupStats: '，去重重复计数{duplicates}，总事件{total}', buckets: '；按类：{buckets}',
          eventsLoaded: '已拉取事件：{id}{extra}', eventQueryFail: '事件查询失败：{error}',
          webhookLoaded: '已拉取 webhook 去重记录：{id}', webhookQueryFail: 'Webhook 记录查询失败：{error}',
          pageLoaded: '页面加载完成'
        },
        'en-US': {
          appTitle: 'AI Outbound Platform', adminPortal: 'Admin Portal', agentPortal: 'Agent Portal',
          demoAccount: 'Default demo account', role: 'Role', apiDocs: 'API Docs',
          accountConnectivity: 'Account & Connectivity', rolePage: 'Role dashboard', username: 'Username', password: 'Password',
          login: 'Sign In', checkMe: 'Check /auth/me', checkDashboard: 'Check dashboard', checkHealth: 'Check Health',
          logout: 'Sign Out', clearLog: 'Clear Log', notLoggedIn: 'Not signed in', notOperated: 'No operation yet',
          contactManagement: 'Contact Management', contactHelp: 'Contacts can be assigned to campaigns in batches',
          phone: 'Mobile Number', name: 'Name', tags: 'Tags', consentState: 'Consent Status', dnc: 'Do Not Call',
          add: 'Add', search: 'Search', consent: 'Consent', dncShort: 'DNC', pagination: 'Pagination', refresh: 'Refresh',
          scriptTemplates: 'Script Templates', templateHelp: 'Create reusable scripts for campaigns',
          templateName: 'Template Name', category: 'Category', enabled: 'Enabled', content: 'Content',
          createTemplate: 'Create Template', templateList: 'Template List', actions: 'Actions', optional: 'Optional',
          fillTemplate: 'Use Selected Template in Campaign', templateHint: 'A new campaign can reference a template ID directly.',
          campaignManagement: 'Campaign Management', campaignHelp: 'Bind contacts and scripts to create outbound tasks',
          campaignName: 'Campaign Name', templateId: 'Template ID', scriptText: 'Script', mode: 'Mode', concurrency: 'Concurrency',
          contactIds: 'Contact IDs', async: 'Async', startHint: 'Start Hint',
          asyncHint: 'Async mode queues first; refresh Calls & Sessions to view the result.', createCampaign: 'Create Campaign',
          campaignList: 'Campaign List', contacts: 'Contacts', status: 'Status', callsSessions: 'Calls & Sessions',
          callsHelp: 'Start a single call or review campaign call status', callPhone: 'Phone Number', startCall: 'Start Call',
          refreshCalls: 'Refresh Calls', phoneShort: 'Phone', retry: 'Retry', systemLog: 'System Log',
          logHelp: 'Key operations are displayed here', waiting: 'Waiting for an operation...', logCleared: 'Log cleared',
          loggedOut: 'Signed out', logoutLog: 'Signed out', restoredSession: 'Session restored', loggingIn: 'Signing in...',
          loginSuccess: 'Signed in: {username}/{role}', loginFail: 'Sign-in failed: {error}',
          authOk: 'Authenticated: {username} ({role})', authFail: 'Authentication failed: {error}', dashboardOk: 'Dashboard accessible',
          dashboardDenied: 'Dashboard denied: {error}', healthOk: 'Health OK: {status}', healthFail: 'Health failed: {error}',
          contactCreated: 'Contact created: ID {id}', createFail: 'Create failed: {error}', contactCount: '{count} contacts',
          pageSummary: 'Page {page} / {size} per page', queryFail: 'Query failed: {error}', yes: 'Yes', no: 'No',
          fillToCampaign: 'Use in Campaign', offline: 'Disable', templateCount: '{count} templates', frontendCreated: 'Created from portal',
          templateCreated: 'Template created: ID {id}', templateOffline: 'Template {id} disabled', updateFail: 'Update failed: {error}',
          templateSelected: 'Template {id} selected', templateReadFail: 'Template read failed: {error}',
          selectTemplate: 'Select a template first', campaignCreated: 'Campaign created: ID {id}',
          campaignCount: '{count} campaigns', start: 'Start', startOne: 'Start (1)', resultCode: ', result code {code}',
          campaignStarted: 'Campaign {id} started, {count} calls{code}', startFail: 'Start failed: {error}',
          callStarted: 'Call started: {id}', callFail: 'Call failed: {error}', callCount: '{count} calls',
          handoff: 'Handoff', hangup: 'Hang Up', retryAction: 'Retry', events: 'Events', webhookDedup: 'Webhook Dedup',
          handoffOk: 'Handoff triggered', handoffFail: 'Handoff failed: ', hangupOk: 'Hangup submitted', hangupFail: 'Hangup failed: ',
          retryOk: 'Retry submitted', retryFail: 'Retry failed: ', dupStats: ', {duplicates} duplicates, {total} total events',
          buckets: '; by type: {buckets}', eventsLoaded: 'Events loaded: {id}{extra}', eventQueryFail: 'Event query failed: {error}',
          webhookLoaded: 'Webhook dedup records loaded: {id}', webhookQueryFail: 'Webhook record query failed: {error}',
          pageLoaded: 'Page loaded'
        }
      };

      const defaultFieldValues = {
        contactName: { 'zh-CN': '演示客户', 'en-US': 'Demo Customer' },
        templateName: { 'zh-CN': '欢迎开场话术', 'en-US': 'Welcome Opening Script' },
        templateContent: {
          'zh-CN': '您好，{客户姓名}，我先为您确认一下订单信息。需要转人工请说“转人工”。',
          'en-US': 'Hello {customer_name}, I would like to confirm your order details. Say “human agent” to request a handoff.'
        },
        campaignName: { 'zh-CN': '演示活动', 'en-US': 'Demo Campaign' },
        campaignScript: {
          'zh-CN': '欢迎致电，先确认用户是否需要服务，再进行后续操作。',
          'en-US': 'Welcome. Confirm whether the customer needs assistance before continuing.'
        }
      };

      function t(key, values={}) {
        const dictionary = translations[state.language] || translations['zh-CN'];
        let text = dictionary[key] || translations['zh-CN'][key] || key;
        for (const [name, value] of Object.entries(values)) {
          text = text.replaceAll(`{${name}}`, String(value ?? ''));
        }
        return text;
      }

      function syncDefaultFieldValues(language) {
        for (const [id, values] of Object.entries(defaultFieldValues)) {
          const element = document.getElementById(id);
          if (!element) continue;
          if (Object.values(values).includes(element.value)) {
            element.value = values[language];
          }
        }
      }

      function applyLanguage(language) {
        state.language = translations[language] ? language : 'zh-CN';
        document.documentElement.lang = state.language;
        document.getElementById('languageSelect').value = state.language;
        syncDefaultFieldValues(state.language);
        document.querySelectorAll('[data-i18n]').forEach((element) => {
          const key = element.dataset.i18n;
          if (key === 'pageTitle') {
            element.textContent = t(state.isAdmin ? 'adminPortal' : 'agentPortal');
          } else {
            element.textContent = t(key);
          }
        });
        document.querySelectorAll('[data-i18n-placeholder]').forEach((element) => {
          element.placeholder = t(element.dataset.i18nPlaceholder);
        });
        const tokenDisplay = document.getElementById('tokenDisplay');
        if (!state.token) tokenDisplay.value = t('notLoggedIn');
        const logArea = document.getElementById('log');
        if (Object.values(translations).some((dictionary) => [dictionary.waiting, dictionary.logCleared].includes(logArea.textContent))) {
          logArea.textContent = t('waiting');
        }
        document.title = `${t('appTitle')} · ${t(state.isAdmin ? 'adminPortal' : 'agentPortal')}`;
      }

      function changeLanguage(language) {
        const nextLanguage = translations[language] ? language : 'zh-CN';
        localStorage.setItem(state.languageStorageKey, nextLanguage);
        applyLanguage(nextLanguage);
        if (state.token || document.getElementById('apiKey').value.trim()) {
          refreshPortalData();
        }
      }

      function log(msg) {
        const area = document.getElementById('log');
        const ts = new Date().toLocaleTimeString(state.language);
        area.textContent = `[${ts}] ${msg}\n` + area.textContent;
      }

      function setStatus(id, text, err=false) {
        const el = document.getElementById(id);
        if (!el) return;
        el.removeAttribute('data-i18n');
        el.className = err ? 'status error' : 'status';
        el.textContent = text || '';
      }

      function getCommonHeaders({auth=true, json=false}={}) {
        const headers = {
          'x-api-key': document.getElementById('apiKey').value.trim(),
          'x-tenant-id': (document.getElementById('tenantId').value || '1').trim(),
        };
        if (auth && state.token) {
          headers.Authorization = `Bearer ${state.token}`;
        }
        if (json) {
          headers['Content-Type'] = 'application/json';
        }
        return headers;
      }

      async function requestJson(url, options={}) {
        const resp = await fetch(url, options);
        const ct = resp.headers.get('content-type') || '';
        const payload = ct.includes('application/json') ? await resp.json() : await resp.text();
        if (!resp.ok) {
          const msg = typeof payload === 'string' ? payload : JSON.stringify(payload);
          throw new Error(`${resp.status} ${resp.statusText} ${msg}`);
        }
        return payload;
      }

      function clearLog() {
        document.getElementById('log').textContent = t('logCleared');
      }

      function saveToken(token) {
        state.token = token || '';
        if (state.token) {
          sessionStorage.setItem(state.storageKey, state.token);
        } else {
          sessionStorage.removeItem(state.storageKey);
        }
        document.getElementById('tokenDisplay').value = state.token ? `${state.token.slice(0, 20)}...` : t('notLoggedIn');
      }

      function logout() {
        saveToken('');
        setStatus('authStatus', t('loggedOut'));
        log(t('logoutLog'));
      }

      function restoreToken() {
        const t = sessionStorage.getItem(state.storageKey);
        if (t) {
          saveToken(t);
          setStatus('authStatus', t('restoredSession'));
        }
      }

      function getSelectedRows(selector) {
        const rows = document.querySelectorAll(`${selector} input[type=checkbox]:checked`);
        return Array.from(rows).map((el) => Number(el.value));
      }

      async function login() {
        setStatus('authStatus', t('loggingIn'));
        try {
          const data = await requestJson('/api/v1/auth/login', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
              username: document.getElementById('username').value.trim(),
              password: document.getElementById('password').value,
            }),
          });
          saveToken(data.access_token);
          setStatus('authStatus', t('loginSuccess', {username: data.username, role: data.role}));
          log(`${t('loginSuccess', {username: data.username, role: data.role})}, tenant=${data.tenant_id}`);
          await checkMe();
          await refreshPortalData();
        } catch (err) {
          setStatus('authStatus', t('loginFail', {error: err.message}), true);
          log(t('loginFail', {error: err.message}));
        }
      }

      async function checkMe() {
        try {
          const data = await requestJson('/api/v1/auth/me', {
            method: 'GET',
            headers: getCommonHeaders(),
          });
          setStatus('authStatus', t('authOk', {username: data.username, role: data.role}));
          log(`auth/me: ${JSON.stringify(data)}`);
        } catch (err) {
          setStatus('authStatus', t('authFail', {error: err.message}), true);
          log(`auth/me: ${err.message}`);
        }
      }

      async function checkDashboard() {
        try {
          const data = await requestJson(state.dashboardApi, {
            method: 'GET',
            headers: getCommonHeaders(),
          });
          setStatus('systemStatus', t('dashboardOk'));
          log(`dashboard: ${JSON.stringify(data)}`);
        } catch (err) {
          setStatus('systemStatus', t('dashboardDenied', {error: err.message}), true);
          log(`dashboard: ${err.message}`);
        }
      }

      async function checkHealth() {
        try {
          const data = await requestJson('/health', {method:'GET'});
          setStatus('systemStatus', t('healthOk', {status: data.status}));
          log(`health: ${JSON.stringify(data)}`);
        } catch (err) {
          setStatus('systemStatus', t('healthFail', {error: err.message}), true);
          log(`health: ${err.message}`);
        }
      }

      async function createContact() {
        try {
          const phone = document.getElementById('contactPhone').value.trim();
          const payload = {
            phone,
            name: document.getElementById('contactName').value.trim(),
            tags: document.getElementById('contactTags').value.trim(),
            consent_state: document.getElementById('contactConsent').value,
            dnc: document.getElementById('contactDnc').checked,
            timezone: 'Asia/Shanghai',
          };
          const data = await requestJson('/api/v1/contacts', {
            method: 'POST',
            headers: getCommonHeaders({json:true}),
            body: JSON.stringify(payload),
          });
          setStatus('contactStatus', t('contactCreated', {id: data.id}));
          log(`create contact: ${JSON.stringify(data)}`);
          await searchContacts(true);
        } catch (err) {
          setStatus('contactStatus', t('createFail', {error: err.message}), true);
          log(`create contact fail: ${err.message}`);
        }
      }

      async function searchContacts(force=false) {
        if (force) {
          state.page.contactsPage = Number(document.getElementById('contactsPage').value) || 1;
          state.page.contactsSize = Number(document.getElementById('contactsSize').value) || 20;
        }
        const page = state.page.contactsPage;
        const size = state.page.contactsSize;
        try {
          const data = await requestJson(`/api/v1/contacts?page=${page}&size=${size}`, {
            headers: getCommonHeaders(),
          });
          const tbody = document.getElementById('contactsTbody');
          tbody.innerHTML = '';
          for (const item of data) {
            const tr = document.createElement('tr');
            tr.innerHTML = `
              <td>${item.id}</td>
              <td>${item.phone}</td>
              <td>${item.name || ''}</td>
              <td>${item.tags || ''}</td>
              <td>${item.consent_state}</td>
              <td>${item.dnc ? t('yes') : t('no')}</td>
            `;
            tbody.appendChild(tr);
          }
          setStatus('contactStatus', t('contactCount', {count: data.length}));
          document.getElementById('contactStatus2').textContent = t('pageSummary', {page, size});
        } catch (err) {
          setStatus('contactStatus', t('queryFail', {error: err.message}), true);
          log(`contacts query fail: ${err.message}`);
        }
      }

      async function searchTemplates(force=false) {
        const page = 1;
        const size = 50;
        try {
          const data = await requestJson(`/api/v1/script-templates?active_only=false&page=${page}&size=${size}`, {
            headers: getCommonHeaders(),
          });
          const tbody = document.getElementById('templatesTbody');
          tbody.innerHTML = '';
          for (const item of data) {
            const tr = document.createElement('tr');
            tr.innerHTML = `
              <td>${item.id}</td>
              <td>
                <label>
                  <input type=\"radio\" name=\"templatePick\" value=\"${item.id}\" onchange=\"window.__portalPickTemplate(${item.id})\"> ${item.name}
                </label>
              </td>
              <td>${item.category || ''}</td>
              <td>${item.is_active ? t('yes') : t('no')}</td>
              <td>
                <div class=\"inline\">
                  <button class=\"secondary\" onclick=\"useTemplateForCampaign(${item.id})\">${t('fillToCampaign')}</button>
                  <button class=\"secondary\" onclick=\"toggleTemplateActive(${item.id}, false)\">${t('offline')}</button>
                </div>
              </td>
            `;
            tbody.appendChild(tr);
          }
          setStatus('templateStatus', t('templateCount', {count: data.length}));
        } catch (err) {
          setStatus('templateStatus', t('queryFail', {error: err.message}), true);
          log(`templates query fail: ${err.message}`);
        }
      }

      async function createTemplate() {
        try {
          const payload = {
            name: document.getElementById('templateName').value.trim(),
            content: document.getElementById('templateContent').value.trim(),
            category: document.getElementById('templateCategory').value.trim(),
            description: t('frontendCreated'),
            tags: document.getElementById('templateTags').value.trim(),
            is_active: document.getElementById('templateActive').checked,
          };
          const data = await requestJson('/api/v1/script-templates', {
            method: 'POST',
            headers: getCommonHeaders({json:true}),
            body: JSON.stringify(payload),
          });
          setStatus('templateStatus', t('templateCreated', {id: data.id}));
          log(`create template: ${JSON.stringify(data)}`);
          await searchTemplates(true);
        } catch (err) {
          setStatus('templateStatus', t('createFail', {error: err.message}), true);
          log(`create template fail: ${err.message}`);
        }
      }

      async function toggleTemplateActive(templateId) {
        try {
          const data = await requestJson(`/api/v1/script-templates/${templateId}`, {
            method: 'PUT',
            headers: getCommonHeaders({json:true}),
            body: JSON.stringify({ is_active: false }),
          });
          setStatus('templateStatus', t('templateOffline', {id: templateId}));
          log(`template offline: ${JSON.stringify(data)}`);
          await searchTemplates(true);
        } catch (err) {
          setStatus('templateStatus', t('updateFail', {error: err.message}), true);
          log(`template offline fail: ${err.message}`);
        }
      }

      async function useTemplateForCampaign(templateId) {
        try {
          const data = await requestJson(`/api/v1/script-templates/${templateId}`, {
            headers: getCommonHeaders(),
          });
          document.getElementById('campaignTemplateId').value = String(templateId);
          if (data && data.content) {
            document.getElementById('campaignScript').value = data.content;
          }
          setStatus('templateStatus', t('templateSelected', {id: templateId}));
          log(`template picked: ${JSON.stringify(data)}`);
        } catch (err) {
          setStatus('templateStatus', t('templateReadFail', {error: err.message}), true);
          log(`template pick fail: ${err.message}`);
        }
      }

      function __portalPickTemplate(id) {
        window._selectedTemplateId = id;
      }

      window.__portalPickTemplate = (id) => {
        window._selectedTemplateId = Number(id);
      };

      function fillActiveTemplateToCampaign() {
        const id = window._selectedTemplateId;
        if (!id) {
          setStatus('templateStatus', t('selectTemplate'), true);
          return;
        }
        document.getElementById('campaignTemplateId').value = String(id);
        setStatus('templateStatus', t('templateSelected', {id}));
      }

      async function createCampaign() {
        try {
          const contactIds = (document.getElementById('campaignContactIds').value || '')
            .split(',')
            .map((x) => Number(x.trim()))
            .filter((x) => Number.isInteger(x) && x > 0);

          const payload = {
            name: document.getElementById('campaignName').value.trim(),
            script: document.getElementById('campaignScript').value.trim(),
            script_template_id: Number(document.getElementById('campaignTemplateId').value) || null,
            mode: document.getElementById('campaignMode').value,
            concurrency: Number(document.getElementById('campaignConcurrency').value) || 5,
            retry_limit: 1,
            retry_interval_sec: 30,
            attempt_interval_sec: 1200,
            recording_enabled: true,
            hangup_sms_enabled: true,
            contact_ids: contactIds,
          };

          const data = await requestJson('/api/v1/campaigns', {
            method: 'POST',
            headers: getCommonHeaders({json:true}),
            body: JSON.stringify(payload),
          });
          setStatus('campaignStatus', t('campaignCreated', {id: data.id}));
          log(`create campaign: ${JSON.stringify(data)}`);
          await searchCampaigns(true);
        } catch (err) {
          setStatus('campaignStatus', t('createFail', {error: err.message}), true);
          log(`create campaign fail: ${err.message}`);
        }
      }

      async function searchCampaigns(force=false) {
        try {
          const data = await requestJson('/api/v1/campaigns?page=1&size=50', {
            headers: getCommonHeaders(),
          });
          const tbody = document.getElementById('campaignsTbody');
          tbody.innerHTML = '';
          for (const item of data) {
            const tr = document.createElement('tr');
            tr.innerHTML = `
              <td>${item.id}</td>
              <td>${item.name}</td>
              <td>${item.mode}</td>
              <td>${(item.contact_ids || []).length || ''}</td>
              <td>${item.status}</td>
              <td>
                <div class=\"inline\">
                  <button class=\"secondary\" onclick=\"startCampaignFromRow(${item.id})\">${t('start')}</button>
                  <button class=\"warn\" onclick=\"startCampaignFromRow(${item.id}, 1)\">${t('startOne')}</button>
                </div>
              </td>
            `;
            tbody.appendChild(tr);
          }
          setStatus('campaignStatus', t('campaignCount', {count: data.length}));
        } catch (err) {
          setStatus('campaignStatus', t('queryFail', {error: err.message}), true);
          log(`campaign query fail: ${err.message}`);
        }
      }

      function startCampaignFromRow(campaignId, maxDial=undefined) {
        return startCampaign(campaignId, maxDial);
      }

      async function startCampaign(campaignId, maxDial) {
        try {
          let url = `/api/v1/campaigns/${campaignId}/start`;
          const asyncDial = document.getElementById('campaignAsyncDial').checked;
          const query = new URLSearchParams();
          query.set('async_dial', asyncDial ? 'true' : 'false');
          query.set('auto_dial', 'true');
          if (maxDial) {
            query.set('max_dials', String(maxDial));
          }
          url += `?${query.toString()}`;
          const data = await requestJson(url, {
            method: 'POST',
            headers: getCommonHeaders(),
          });
          const code = data.result_code ? t('resultCode', {code: data.result_code}) : '';
          setStatus('campaignStatus', t('campaignStarted', {
            id: campaignId,
            count: data.auto_dial_count || 0,
            code,
          }));
          log(`campaign start: ${JSON.stringify(data)}`);
          if (data.dispatch_result) {
            log(`campaign dispatch: ${JSON.stringify(data.dispatch_result)}`);
          }
          if (data.error_codes && data.error_codes.length) {
            log(`campaign error codes: ${JSON.stringify(data.error_codes)}`);
          }
          await searchCalls(true);
        } catch (err) {
          setStatus('campaignStatus', t('startFail', {error: err.message}), true);
          log(`campaign start fail: ${err.message}`);
        }
      }

      async function createCall() {
        try {
          const payload = {
            phone: document.getElementById('callPhone').value.trim(),
            mode: document.getElementById('callMode').value,
            campaign_id: Number(document.getElementById('callCampaignId').value) || null,
            contact_id: Number(document.getElementById('callContactId').value) || null,
            max_attempts: Number(document.getElementById('callMaxAttempts').value) || 1,
          };
          const data = await requestJson('/api/v1/calls', {
            method: 'POST',
            headers: getCommonHeaders({json:true}),
            body: JSON.stringify(payload),
          });
          setStatus('callStatus', t('callStarted', {id: data.id}));
          log(`create call: ${JSON.stringify(data)}`);
          await searchCalls(true);
        } catch (err) {
          setStatus('callStatus', t('callFail', {error: err.message}), true);
          log(`create call fail: ${err.message}`);
        }
      }

      async function searchCalls(force=false) {
        try {
          const page = Number(document.getElementById('callsPage')?.value || 1) || 1;
          const size = Number(document.getElementById('callsSize')?.value || 20) || 20;
          const data = await requestJson(`/api/v1/calls?page=${page}&size=${size}`, {
            headers: getCommonHeaders(),
          });
          const tbody = document.getElementById('callsTbody');
          tbody.innerHTML = '';
          for (const item of data) {
            const tr = document.createElement('tr');
            tr.innerHTML = `
              <td>${item.id}</td>
              <td>${item.phone}</td>
              <td>${item.mode}</td>
              <td>${item.status}</td>
              <td>${item.attempts}/${item.max_attempts}</td>
              <td>${item.campaign_id || ''}</td>
              <td>${item.contact_id || ''}</td>
              <td>
                <div class=\"inline\">
                  <button class=\"warn\" onclick=\"handoffCall('${item.id}')\">${t('handoff')}</button>
                  <button class=\"danger\" onclick=\"hangupCall('${item.id}')\">${t('hangup')}</button>
                  <button class=\"secondary\" onclick=\"retryCall('${item.id}')\">${t('retryAction')}</button>
                  <button class=\"secondary\" onclick=\"openEvents('${item.id}')\">${t('events')}</button>
                  <button class=\"secondary\" onclick=\"openWebhookEvents('${item.id}')\">${t('webhookDedup')}</button>
                </div>
              </td>
            `;
            tbody.appendChild(tr);
          }
          setStatus('callStatus', t('callCount', {count: data.length}));
        } catch (err) {
          setStatus('callStatus', t('queryFail', {error: err.message}), true);
          log(`calls query fail: ${err.message}`);
        }
      }

      async function handoffCall(callId) {
        await actionCall(`/api/v1/calls/${callId}/handover`, t('handoffOk'), t('handoffFail'));
      }

      async function hangupCall(callId) {
        await actionCall(`/api/v1/calls/${callId}/hangup?reason=manual`, t('hangupOk'), t('hangupFail'));
      }

      async function retryCall(callId) {
        await actionCall(`/api/v1/calls/${callId}/retry`, t('retryOk'), t('retryFail'));
      }

      async function actionCall(url, okText, failPrefix) {
        try {
          const data = await requestJson(url, {
            method: 'POST',
            headers: getCommonHeaders(),
          });
          setStatus('callStatus', okText);
          log(`${okText} ${JSON.stringify(data)}`);
          await searchCalls(true);
        } catch (err) {
          setStatus('callStatus', `${failPrefix}${err.message}`, true);
          log(`${failPrefix}${err.message}`);
        }
      }

      async function openEvents(callId) {
        try {
          const [rawEvents, stats] = await Promise.all([
            requestJson(`/api/v1/calls/${callId}/events?page=1&size=20`, {
              headers: getCommonHeaders(),
            }),
            requestJson(`/api/v1/calls/${callId}/webhook-stats`, {
              headers: getCommonHeaders(),
            }),
          ]);
          const data = rawEvents;
          const dupText = stats && stats.duplicate_estimate !== undefined
            ? t('dupStats', {duplicates: stats.duplicate_estimate, total: stats.total})
            : '';
          const bucketText = Array.isArray(stats?.buckets) && stats.buckets.length
            ? t('buckets', {buckets: stats.buckets.map((item) => `${item.source}:${item.event_type}=${item.count}`).join(', ')})
            : '';
          const extra = `${dupText}${bucketText}`;
          log(`events ${callId}: ${JSON.stringify(data)}${extra}`);
          setStatus('callStatus', t('eventsLoaded', {id: callId, extra}));
        } catch (err) {
          setStatus('callStatus', t('eventQueryFail', {error: err.message}), true);
          log(`events fail ${callId}: ${err.message}`);
        }
      }

      async function openWebhookEvents(callId) {
        try {
          const data = await requestJson(`/api/v1/calls/${callId}/webhook-events?page=1&size=20`, {
            headers: getCommonHeaders(),
          });
          log(`webhook-events ${callId}: ${JSON.stringify(data)}`);
          setStatus('callStatus', t('webhookLoaded', {id: callId}));
        } catch (err) {
          setStatus('callStatus', t('webhookQueryFail', {error: err.message}), true);
          log(`webhook-events fail ${callId}: ${err.message}`);
        }
      }

      function toggleAdminBlocks() {
        if (!state.isAdmin) {
          document.querySelectorAll('.col-12.__ADMIN_ONLY__, .col-4.__ADMIN_ONLY__').forEach((el) => {
            el.classList.add('hidden');
          });
        }
      }

      async function refreshPortalData() {
        const tasks = [searchCalls(true)];
        if (state.isAdmin) {
          tasks.push(searchContacts(true), searchTemplates(true), searchCampaigns(true));
        }
        await Promise.all(tasks);
      }

      window.addEventListener('load', async () => {
        const savedLanguage = localStorage.getItem(state.languageStorageKey) || 'zh-CN';
        applyLanguage(savedLanguage);
        restoreToken();
        toggleAdminBlocks();
        if (state.token || document.getElementById('apiKey').value.trim()) {
          await refreshPortalData();
        }
        log(t('pageLoaded'));
      });
    </script>
  </body>
</html>
"""

    selected = "" if show_manage else "hidden"
    return (
        page.replace("__TITLE__", page_title)
        .replace("__DEFAULT_USERNAME__", default_username)
        .replace("__DEFAULT_PASSWORD__", default_password)
        .replace("__DEFAULT_ROLE__", default_role)
        .replace("__DASHBOARD_PATH__", dashboard_path)
        .replace("__DEFAULT_API_KEY__", api_key)
        .replace("__IS_ADMIN__", "'1'" if show_manage else "'0'")
        .replace("__ADMIN_ONLY__", selected)
    )


@router.get("/api/v1/admin/dashboard")
def admin_dashboard(_=Depends(require_role("admin"))):
    return {"scope": "admin", "message": "管理员控制台"}


@router.get("/api/v1/agent/dashboard")
def agent_dashboard(_=Depends(require_roles("agent", "admin"))):
    return {"scope": "agent", "message": "座席工作台"}


@router.get("/admin", response_class=HTMLResponse)
def admin_page():
    return _portal_page(
        page_title="管理员端",
        default_username=settings.demo_admin_username,
        default_password=settings.demo_admin_password,
        default_role="admin",
        dashboard_path="admin/dashboard",
        api_key="",
        show_manage=True,
    )


@router.get("/agent", response_class=HTMLResponse)
def agent_page():
    return _portal_page(
        page_title="座席端",
        default_username=settings.demo_agent_username,
        default_password=settings.demo_agent_password,
        default_role="agent",
        dashboard_path="agent/dashboard",
        api_key="",
        show_manage=False,
    )


@router.get("/docs.html")
def docs_page():
    return RedirectResponse(url="/docs")
