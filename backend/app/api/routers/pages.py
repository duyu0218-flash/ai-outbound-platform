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
      @media (max-width: 1280px) {
        .col-3, .col-4, .col-6, .col-8, .col-12 { grid-column: span 12; }
      }
    </style>
  </head>
  <body>
    <div class="layout">
      <div class="topbar">
        <div>
          <h1>AI 外呼平台 · __TITLE__</h1>
          <div class="muted">默认演示账号：__DEFAULT_USERNAME__ / __DEFAULT_PASSWORD__（角色：__DEFAULT_ROLE__）</div>
        </div>
        <div class="btns">
          <a href="/admin">管理员端</a>
          <a href="/agent">座席端</a>
          <a href="/docs.html">API 文档</a>
          <a href="/health">/health</a>
          <a href="/healthz">/healthz</a>
        </div>
      </div>

      <div class="card">
        <div class="toolbar">
          <h2>账户与连通性</h2>
          <div class="small">系统角色页：__DASHBOARD_PATH__</div>
        </div>
        <div class="row"><label>用户名</label><input id="username" value="__DEFAULT_USERNAME__" /></div>
        <div class="row"><label>密码</label><input id="password" type="password" value="__DEFAULT_PASSWORD__" /></div>
        <div class="row"><label>API Key</label><input id="apiKey" value="__DEFAULT_API_KEY__" /></div>
        <div class="row"><label>Tenant ID</label><input id="tenantId" value="1" /></div>
        <div class="row"><label>Token</label><input id="tokenDisplay" readonly value="未登录" /></div>
        <div class="btns">
          <button onclick="login()">登录</button>
          <button class="secondary" onclick="checkMe()">检查 /auth/me</button>
          <button class="secondary" onclick="checkDashboard()">检查 dashboard</button>
          <button class="secondary" onclick="checkHealth()">检查服务健康</button>
          <button class="danger" onclick="logout()">退出</button>
          <button class="secondary" onclick="clearLog()">清日志</button>
        </div>
        <div class="row">
          <span id="authStatus" class="status">未登录</span>
          <span id="systemStatus" class="status"></span>
        </div>
      </div>

      <div class="grid">
        <section class="card col-4 __ADMIN_ONLY__">
          <div class="toolbar">
            <h2>联系人管理</h2>
            <div class="small">联系人可批量用于活动</div>
          </div>
          <div class="row"><label>手机号</label><input id="contactPhone" value="13800000000" /></div>
          <div class="row"><label>姓名</label><input id="contactName" value="演示客户" /></div>
          <div class="row"><label>标签</label><input id="contactTags" value="demo" /></div>
          <div class="row"><label>同意态</label>
            <select id="contactConsent">
              <option value="unknown">unknown</option>
              <option value="consented" selected>consented</option>
              <option value="not_consented">not_consented</option>
              <option value="revoked">revoked</option>
            </select>
          </div>
          <div class="row"><label>禁打标识</label><input id="contactDnc" type="checkbox" /></div>
          <div class="btns">
            <button onclick="createContact()">新增</button>
            <button class="secondary" onclick="searchContacts()">查询</button>
          </div>
          <p id="contactStatus" class="status">未操作</p>
          <div style="max-height: 240px; overflow:auto;">
            <table>
              <thead>
                <tr><th>ID</th><th>手机号</th><th>姓名</th><th>标签</th><th>同意</th><th>禁打</th></tr>
              </thead>
              <tbody id="contactsTbody"></tbody>
            </table>
          </div>
          <div class="row">
            <label>分页</label>
            <input id="contactsPage" type="number" value="1" min="1" style="max-width:96px;" />
            <input id="contactsSize" type="number" value="20" min="1" max="100" style="max-width:96px;" />
            <button class="secondary" onclick="searchContacts(true)">刷新</button>
          </div>
          <div id="contactStatus2" class="small"></div>
        </section>

        <section class="card col-8 __ADMIN_ONLY__">
          <div class="toolbar">
            <h2>话术模板</h2>
            <div class="small">支持创建模板后，在活动中复用</div>
          </div>
          <div class="row"><label>模板名称</label><input id="templateName" value="欢迎开场话术" /></div>
          <div class="row"><label>分类</label><input id="templateCategory" value="default" /></div>
          <div class="row"><label>标签</label><input id="templateTags" value="demo" /></div>
          <div class="row"><label>是否启用</label><input id="templateActive" type="checkbox" checked /></div>
          <div class="row"><label>内容</label><textarea id="templateContent">您好，{客户姓名}，我先为您确认一下订单信息。需要转人工请说“转人工”。</textarea></div>
          <div class="btns">
            <button onclick="createTemplate()">创建模板</button>
            <button class="secondary" onclick="searchTemplates(true)">模板列表</button>
          </div>
          <p id="templateStatus" class="status">未操作</p>
          <div style="max-height: 240px; overflow:auto;">
            <table>
              <thead>
                <tr><th>ID</th><th>名称</th><th>分类</th><th>是否启用</th><th>操作</th></tr>
              </thead>
              <tbody id="templatesTbody"></tbody>
            </table>
          </div>
          <div class="row">
            <label>选填</label>
            <button class="secondary" onclick="fillActiveTemplateToCampaign()">将已选模板填入活动话术</button>
            <span id="templateHint" class="small">新建活动可直接选择模板ID。</span>
          </div>
        </section>

        <section class="card col-12 __ADMIN_ONLY__">
          <div class="toolbar">
            <h2>活动管理</h2>
            <div class="small">绑定联系人与话术后可自动创建外呼任务</div>
          </div>
          <div class="row"><label>活动名称</label><input id="campaignName" value="演示活动" /></div>
          <div class="row"><label>模板ID</label><input id="campaignTemplateId" placeholder="可选" /></div>
          <div class="row"><label>话术文本</label><textarea id="campaignScript">欢迎致电，先确认用户是否需要服务，再进行后续操作。</textarea></div>
          <div class="row">
            <label>模式</label>
            <select id="campaignMode">
              <option value="ai_handoff" selected>ai_handoff</option>
              <option value="ai_only">ai_only</option>
              <option value="human_only">human_only</option>
              <option value="ai_with_sms">ai_with_sms</option>
              <option value="mixed_human_first">mixed_human_first</option>
            </select>
            <label>并发</label><input id="campaignConcurrency" value="5" type="number" min="1" style="max-width:96px;" />
          </div>
          <div class="row">
            <label>联系人ID</label><input id="campaignContactIds" value="" placeholder="1,2,3" />
            <label>max_dials</label><input id="campaignMaxDials" value="1" type="number" min="1" style="max-width:96px;" />
            <label>异步</label><input id="campaignAsyncDial" type="checkbox" checked />
          </div>
          <div class="row">
            <label>启动提示</label><span class="small">异步模式下先返回排队结果，再到“外呼与会话”页刷新状态</span>
          </div>
          <div class="btns">
            <button onclick="createCampaign()">创建活动</button>
            <button class="secondary" onclick="searchCampaigns(true)">活动列表</button>
          </div>
          <p id="campaignStatus" class="status">未操作</p>
          <div style="max-height: 320px; overflow:auto;">
            <table>
              <thead>
                <tr><th>ID</th><th>名称</th><th>模式</th><th>联系人</th><th>状态</th><th>操作</th></tr>
              </thead>
              <tbody id="campaignsTbody"></tbody>
            </table>
          </div>
        </section>

        <section class="card col-12">
          <div class="toolbar">
            <h2>外呼与会话</h2>
            <div class="small">支持单路发起外呼/启动活动回传状态</div>
          </div>
          <div class="row"><label>呼叫手机号</label><input id="callPhone" value="13800000000" /></div>
          <div class="row">
            <label>模式</label>
            <select id="callMode">
              <option value="ai_only">ai_only</option>
              <option value="ai_handoff" selected>ai_handoff</option>
              <option value="human_only">human_only</option>
              <option value="ai_with_sms">ai_with_sms</option>
              <option value="mixed_human_first">mixed_human_first</option>
            </select>
            <label>campaign_id</label><input id="callCampaignId" type="number" min="1" placeholder="可选" />
            <label>contact_id</label><input id="callContactId" type="number" min="1" placeholder="可选" />
            <label>max_attempts</label><input id="callMaxAttempts" value="1" type="number" min="1" style="max-width:90px;" />
          </div>
          <div class="btns">
            <button onclick="createCall()">发起外呼</button>
            <button class="secondary" onclick="searchCalls(true)">刷新会话</button>
          </div>
          <p id="callStatus" class="status">未操作</p>
          <div class="row">
            <label>分页</label>
            <input id="callsPage" type="number" value="1" min="1" style="max-width:96px;" />
            <input id="callsSize" type="number" value="20" min="1" max="100" style="max-width:96px;" />
          </div>
          <div style="overflow:auto;">
            <table>
              <thead>
                <tr><th>ID</th><th>电话</th><th>模式</th><th>状态</th><th>重试</th><th>campaign_id</th><th>contact_id</th><th>操作</th></tr>
              </thead>
              <tbody id="callsTbody"></tbody>
            </table>
          </div>
        </section>

        <section class="card col-12">
          <div class="toolbar">
            <h2>系统日志</h2>
            <div class="small">所有关键操作会回显到此处</div>
          </div>
          <pre id="log">等待操作...</pre>
        </section>
      </div>
    </div>

    <script>
      const state = {
        token: '',
        storageKey: 'ai-platform-token-__DEFAULT_ROLE__',
        selectedTemplateId: '',
        campaignScriptFromTemplate: '',
        dashboardApi: '/api/v1/__DASHBOARD_PATH__',
        isAdmin: __IS_ADMIN__ === '1',
        page: { contactsPage:1, campaignsPage:1, callsPage:1 },
      };

      const formatStatus = (text, err=false) => `<span class="${err ? 'error' : ''}">${text || ''}</span>`;

      function log(msg) {
        const area = document.getElementById('log');
        const ts = new Date().toLocaleTimeString();
        area.textContent = `[${ts}] ${msg}\n` + area.textContent;
      }

      function setStatus(id, text, err=false) {
        const el = document.getElementById(id);
        if (!el) return;
        el.className = err ? 'status error' : 'status';
        el.innerHTML = text || '';
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
        document.getElementById('log').textContent = '日志已清空';
      }

      function saveToken(token) {
        state.token = token || '';
        if (state.token) {
          sessionStorage.setItem(state.storageKey, state.token);
        } else {
          sessionStorage.removeItem(state.storageKey);
        }
        document.getElementById('tokenDisplay').value = state.token ? `${state.token.slice(0, 20)}...` : '未登录';
      }

      function logout() {
        saveToken('');
        setStatus('authStatus', '已退出');
        log('退出登录');
      }

      function restoreToken() {
        const t = sessionStorage.getItem(state.storageKey);
        if (t) {
          saveToken(t);
          setStatus('authStatus', '已恢复本地会话');
        }
      }

      function getSelectedRows(selector) {
        const rows = document.querySelectorAll(`${selector} input[type=checkbox]:checked`);
        return Array.from(rows).map((el) => Number(el.value));
      }

      async function login() {
        setStatus('authStatus', '登录中...');
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
          setStatus('authStatus', `登录成功：${data.username}/${data.role}`);
          log(`登录成功: ${data.username}/${data.role}, tenant=${data.tenant_id}`);
          await checkMe();
          await refreshPortalData();
        } catch (err) {
          setStatus('authStatus', `登录失败：${err.message}`, true);
          log(`登录失败: ${err.message}`);
        }
      }

      async function checkMe() {
        try {
          const data = await requestJson('/api/v1/auth/me', {
            method: 'GET',
            headers: getCommonHeaders(),
          });
          setStatus('authStatus', `鉴权通过：${data.username}（${data.role}）`);
          log(`auth/me: ${JSON.stringify(data)}`);
        } catch (err) {
          setStatus('authStatus', `鉴权失败：${err.message}`, true);
          log(`auth/me: ${err.message}`);
        }
      }

      async function checkDashboard() {
        try {
          const data = await requestJson(state.dashboardApi, {
            method: 'GET',
            headers: getCommonHeaders(),
          });
          setStatus('systemStatus', `dashboard 可访问`);
          log(`dashboard: ${JSON.stringify(data)}`);
        } catch (err) {
          setStatus('systemStatus', `dashboard 拒绝：${err.message}`, true);
          log(`dashboard: ${err.message}`);
        }
      }

      async function checkHealth() {
        try {
          const data = await requestJson('/health', {method:'GET'});
          setStatus('systemStatus', `health ok: ${data.status}`);
          log(`health: ${JSON.stringify(data)}`);
        } catch (err) {
          setStatus('systemStatus', `health fail: ${err.message}`, true);
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
          setStatus('contactStatus', `联系人创建成功：ID ${data.id}`);
          log(`create contact: ${JSON.stringify(data)}`);
          await searchContacts(true);
        } catch (err) {
          setStatus('contactStatus', `创建失败：${err.message}`, true);
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
              <td>${item.dnc ? '是' : '否'}</td>
            `;
            tbody.appendChild(tr);
          }
          setStatus('contactStatus', `联系人共 ${data.length} 条`);
          document.getElementById('contactStatus2').textContent = `分页：第 ${page} 页 / 每页 ${size} 条`;
        } catch (err) {
          setStatus('contactStatus', `查询失败：${err.message}`, true);
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
              <td>${item.is_active ? '是' : '否'}</td>
              <td>
                <div class=\"inline\">
                  <button class=\"secondary\" onclick=\"useTemplateForCampaign(${item.id})\">填充到活动</button>
                  <button class=\"secondary\" onclick=\"toggleTemplateActive(${item.id}, false)\">下线</button>
                </div>
              </td>
            `;
            tbody.appendChild(tr);
          }
          setStatus('templateStatus', `模板共 ${data.length} 条`);
        } catch (err) {
          setStatus('templateStatus', `查询失败：${err.message}`, true);
          log(`templates query fail: ${err.message}`);
        }
      }

      async function createTemplate() {
        try {
          const payload = {
            name: document.getElementById('templateName').value.trim(),
            content: document.getElementById('templateContent').value.trim(),
            category: document.getElementById('templateCategory').value.trim(),
            description: '前端创建',
            tags: document.getElementById('templateTags').value.trim(),
            is_active: document.getElementById('templateActive').checked,
          };
          const data = await requestJson('/api/v1/script-templates', {
            method: 'POST',
            headers: getCommonHeaders({json:true}),
            body: JSON.stringify(payload),
          });
          setStatus('templateStatus', `模板创建成功：ID ${data.id}`);
          log(`create template: ${JSON.stringify(data)}`);
          await searchTemplates(true);
        } catch (err) {
          setStatus('templateStatus', `创建失败：${err.message}`, true);
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
          setStatus('templateStatus', `模板 ${templateId} 已下线`);
          log(`template offline: ${JSON.stringify(data)}`);
          await searchTemplates(true);
        } catch (err) {
          setStatus('templateStatus', `更新失败：${err.message}`, true);
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
          setStatus('templateStatus', `已选择模板 ${templateId}`);
          log(`template picked: ${JSON.stringify(data)}`);
        } catch (err) {
          setStatus('templateStatus', `模板读取失败：${err.message}`, true);
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
          setStatus('templateStatus', '请先选择一个模板', true);
          return;
        }
        document.getElementById('campaignTemplateId').value = String(id);
        setStatus('templateStatus', `已选择模板 ${id}`);
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
          setStatus('campaignStatus', `活动创建成功：ID ${data.id}`);
          log(`create campaign: ${JSON.stringify(data)}`);
          await searchCampaigns(true);
        } catch (err) {
          setStatus('campaignStatus', `创建失败：${err.message}`, true);
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
                  <button class=\"secondary\" onclick=\"startCampaignFromRow(${item.id})\">启动</button>
                  <button class=\"warn\" onclick=\"startCampaignFromRow(${item.id}, 1)\">启动(1条)</button>
                </div>
              </td>
            `;
            tbody.appendChild(tr);
          }
          setStatus('campaignStatus', `活动共 ${data.length} 条`);
        } catch (err) {
          setStatus('campaignStatus', `查询失败：${err.message}`, true);
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
          const code = data.result_code ? `，结果码 ${data.result_code}` : '';
          setStatus('campaignStatus', `活动 ${campaignId} 启动，拨号${data.auto_dial_count || 0}个${code}`);
          log(`campaign start: ${JSON.stringify(data)}`);
          if (data.dispatch_result) {
            log(`campaign dispatch: ${JSON.stringify(data.dispatch_result)}`);
          }
          if (data.error_codes && data.error_codes.length) {
            log(`campaign error codes: ${JSON.stringify(data.error_codes)}`);
          }
          await searchCalls(true);
        } catch (err) {
          setStatus('campaignStatus', `启动失败：${err.message}`, true);
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
          setStatus('callStatus', `外呼已发起：${data.id}`);
          log(`create call: ${JSON.stringify(data)}`);
          await searchCalls(true);
        } catch (err) {
          setStatus('callStatus', `外呼失败：${err.message}`, true);
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
                  <button class=\"warn\" onclick=\"handoffCall('${item.id}')\">转人工</button>
                  <button class=\"danger\" onclick=\"hangupCall('${item.id}')\">挂断</button>
                  <button class=\"secondary\" onclick=\"retryCall('${item.id}')\">重试</button>
                  <button class=\"secondary\" onclick=\"openEvents('${item.id}')\">事件</button>
                  <button class=\"secondary\" onclick=\"openWebhookEvents('${item.id}')\">Webhook去重</button>
                </div>
              </td>
            `;
            tbody.appendChild(tr);
          }
          setStatus('callStatus', `通话 ${data.length} 条`);
        } catch (err) {
          setStatus('callStatus', `查询失败：${err.message}`, true);
          log(`calls query fail: ${err.message}`);
        }
      }

      async function handoffCall(callId) {
        await actionCall(`/api/v1/calls/${callId}/handover`, '转人工已触发', `转人工失败：`);
      }

      async function hangupCall(callId) {
        await actionCall(`/api/v1/calls/${callId}/hangup?reason=manual`, '挂断已提交', `挂断失败：`);
      }

      async function retryCall(callId) {
        await actionCall(`/api/v1/calls/${callId}/retry`, '重试已提交', `重试失败：`);
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
            ? `，去重重复计数${stats.duplicate_estimate}，总事件${stats.total}`
            : '';
          const bucketText = Array.isArray(stats?.buckets) && stats.buckets.length
            ? `；按类：${stats.buckets.map((item) => `${item.source}:${item.event_type}=${item.count}`).join('，')}`
            : '';
          const extra = `${dupText}${bucketText}`;
          log(`events ${callId}: ${JSON.stringify(data)}${extra}`);
          setStatus('callStatus', `已拉取事件：${callId}${extra}`);
        } catch (err) {
          setStatus('callStatus', `事件查询失败：${err.message}`, true);
          log(`events fail ${callId}: ${err.message}`);
        }
      }

      async function openWebhookEvents(callId) {
        try {
          const data = await requestJson(`/api/v1/calls/${callId}/webhook-events?page=1&size=20`, {
            headers: getCommonHeaders(),
          });
          log(`webhook-events ${callId}: ${JSON.stringify(data)}`);
          setStatus('callStatus', `已拉取 webhook 去重记录：${callId}`);
        } catch (err) {
          setStatus('callStatus', `Webhook 记录查询失败：${err.message}`, true);
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
        restoreToken();
        toggleAdminBlocks();
        if (state.token || document.getElementById('apiKey').value.trim()) {
          await refreshPortalData();
        }
        log('页面加载完成');
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
