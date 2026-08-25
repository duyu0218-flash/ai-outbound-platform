from fastapi import APIRouter
from fastapi.responses import HTMLResponse, RedirectResponse

from ...config import get_settings

settings = get_settings()

router = APIRouter(tags=["pages"])


def _portal_page(
    page_title: str,
    default_username: str,
    default_password: str,
    default_role: str,
    dashboard_path: str,
    api_key: str,
) -> str:
    page = """\
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>AI 外呼平台 · __TITLE__</title>
    <style>
      :root {
        --bg: #eef2ff;
        --panel: #ffffff;
        --line: #d4dcf1;
        --text: #101828;
        --muted: #6b7280;
        --primary: #2563eb;
        --primary-dark: #1d4ed8;
      }

      * { box-sizing: border-box; }
      body {
        margin: 0;
        padding: 24px;
        background: var(--bg);
        color: var(--text);
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Microsoft YaHei", sans-serif;
      }
      .layout {
        max-width: 1240px;
        margin: 0 auto;
      }
      .topbar {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: 12px;
        margin-bottom: 16px;
      }
      h1 {
        margin: 0;
        font-size: 22px;
      }
      .muted { color: var(--muted); font-size: 13px; }
      .row {
        margin: 10px 0;
        display: flex;
        align-items: center;
        flex-wrap: wrap;
        gap: 10px;
      }
      .grid {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 16px;
      }
      .card {
        background: var(--panel);
        border: 1px solid var(--line);
        border-radius: 10px;
        padding: 14px;
      }
      .card h2 {
        margin: 0 0 10px;
        font-size: 16px;
      }
      label {
        width: 120px;
        color: #344054;
        font-size: 13px;
      }
      input, select {
        flex: 1;
        min-width: 180px;
        max-width: 320px;
        padding: 8px 10px;
        border: 1px solid #d0d5dd;
        border-radius: 6px;
      }
      textarea {
        width: 100%;
        min-height: 72px;
        border: 1px solid #d0d5dd;
        border-radius: 6px;
        padding: 8px 10px;
      }
      .btn-group {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
      }
      button {
        border: none;
        border-radius: 6px;
        padding: 8px 12px;
        color: #fff;
        background: var(--primary);
        cursor: pointer;
      }
      button:hover { background: var(--primary-dark); }
      button.secondary {
        background: #475467;
      }
      button.secondary:hover { background: #344054; }
      .status {
        font-size: 13px;
        margin-top: 8px;
        color: #1e7e34;
      }
      .status.error { color: #b42318; }
      pre {
        margin: 6px 0 0;
        padding: 12px;
        background: #0f172a;
        color: #e2e8f0;
        border-radius: 8px;
        max-height: 320px;
        overflow: auto;
        white-space: pre-wrap;
        line-height: 1.35;
      }
      .help { font-size: 12px; color: #6b7280; line-height: 1.6; margin-top: 8px; }
      a { color: var(--primary); text-decoration: none; }
      a:hover { text-decoration: underline; }
      @media (max-width: 980px) {
        .grid { grid-template-columns: 1fr; }
        .topbar { flex-direction: column; align-items: flex-start; }
      }
    </style>
  </head>
  <body>
    <div class="layout">
      <div class="topbar">
        <div>
          <h1>AI 外呼平台 · __TITLE__</h1>
          <p class="muted">默认测试账号：__DEFAULT_USERNAME__ / __DEFAULT_PASSWORD__（角色：__DEFAULT_ROLE__）</p>
        </div>
        <div class="btn-group">
          <a href="/admin">管理员端</a> |
          <a href="/agent">座席端</a> |
          <a href="/docs.html" target="_blank">API文档</a> |
          <a href="/health">/health</a>
        </div>
      </div>

      <div class="grid">
        <section class="card">
          <h2>会话与鉴权</h2>
          <div class="row"><label>用户名</label><input id="username" value="__DEFAULT_USERNAME__" /></div>
          <div class="row"><label>密码</label><input id="password" type="password" value="__DEFAULT_PASSWORD__" /></div>
          <div class="row"><label>API Key</label><input id="apiKey" value="__DEFAULT_API_KEY__" /></div>
          <div class="row"><label>Tenant ID</label><input id="tenantId" value="1" /></div>
          <div class="row"><label>当前Token</label><input id="tokenDisplay" readonly value="未登录" /></div>
          <div class="btn-group">
            <button onclick="login()">登录</button>
            <button class=\"secondary\" onclick="logout()">退出</button>
            <button class=\"secondary\" onclick="clearLog()">清空日志</button>
          </div>
          <p id="authStatus" class="status">未登录</p>
          <p class="help">说明：登录后可调用 /auth/me 和 /api/v1/*/dashboard；联系人/活动/外呼接口需要 API Key 与 Tenant。</p>
        </section>

        <section class="card">
          <h2>系统与角色验证</h2>
          <div class="btn-group">
            <button onclick="checkHealth()">检查健康</button>
            <button class=\"secondary\" onclick="checkMe()">检查 me</button>
            <button onclick="checkDashboard()">检查工作台入口</button>
          </div>
          <p id="systemStatus" class="status">未检测</p>
          <p class="help">工作台接口：__DASHBOARD_PATH__</p>
          <p class="help">成功后建议依次执行：联系人新增 → 活动创建 → 启动活动 → 发起单通外呼 → 查询通话列表。</p>
        </section>
      </div>

      <div class="grid" style="margin-top:16px;">
        <section class="card">
          <h2>联系人管理</h2>
          <div class="row"><label>手机号</label><input id="contactPhone" value="13800000000" /></div>
          <div class="row"><label>姓名</label><input id="contactName" value="测试用户" /></div>
          <div class="row"><label>标签</label><input id="contactTags" value="demo" /></div>
          <div class="row"><label>同意态</label>
            <select id="contactConsent">
              <option value="unknown">unknown</option>
              <option value="consented">consented</option>
              <option value="not_consented">not_consented</option>
              <option value="revoked">revoked</option>
            </select>
          </div>
          <div class="btn-group">
            <button onclick="createContact()">新增联系人</button>
            <button class=\"secondary\" onclick="listContacts()">列表查询</button>
          </div>
          <p id="contactStatus" class="status">尚未操作</p>
        </section>

        <section class="card">
          <h2>活动与外呼</h2>
          <div class="row"><label>活动名称</label><input id="campaignName" value="演示活动" /></div>
          <div class="row"><label>话术文本</label><textarea id="campaignScript">欢迎致电，按流程为客户提供服务。需要转人工请回复 人工。</textarea></div>
          <div class="row"><label>模式</label>
            <select id="campaignMode">
              <option value="ai_handoff" selected>ai_handoff</option>
              <option value="ai_only">ai_only</option>
              <option value="human_only">human_only</option>
              <option value="ai_with_sms">ai_with_sms</option>
              <option value="mixed_human_first">mixed_human_first</option>
            </select>
          </div>
          <div class="row"><label>联系人ID</label><input id="campaignContactIds" value="1" /></div>
          <div class="row"><label>max_dials</label><input id="campaignMaxDials" value="10" /></div>
          <div class="btn-group">
            <button onclick="createCampaign()">创建活动</button>
            <button class=\"secondary\" onclick="startCampaign()">启动活动</button>
          </div>
          <div class="row"><label>外呼手机号</label><input id="callPhone" value="13800000000" /></div>
          <div class="row"><label>外呼模式</label>
            <select id="callMode">
              <option value="ai_only">ai_only</option>
              <option value="human_only">human_only</option>
              <option value="ai_handoff" selected>ai_handoff</option>
              <option value="ai_with_sms">ai_with_sms</option>
              <option value="mixed_human_first">mixed_human_first</option>
            </select>
          </div>
          <div class="btn-group">
            <button onclick="createCall()">发起外呼</button>
            <button class=\"secondary\" onclick="listCalls()">查询通话</button>
          </div>
          <p id="operateStatus" class="status">尚未操作</p>
        </section>
      </div>

      <section class="card" style="margin-top:16px;">
        <h2>统一日志</h2>
        <pre id="log">等待操作...</pre>
      </section>
    </div>

    <script>
      const state = {
        token: '',
        dashboardPath: '/api/v1/__DASHBOARD_PATH__',
        storageKey: 'ai_platform_token___DEFAULT_ROLE__',
      };

      function setStatus(id, text, err = false) {
        const el = document.getElementById(id);
        if (!el) return;
        el.className = err ? 'status error' : 'status';
        el.textContent = text || '';
      }

      function appendLog(msg) {
        const logger = document.getElementById('log');
        const now = new Date().toLocaleTimeString();
        logger.textContent = `[${now}] ${msg}\n` + logger.textContent;
      }

      function setToken(token) {
        state.token = token || '';
        document.getElementById('tokenDisplay').value = token ? `${token.slice(0, 16)}...` : '未登录';
      }

      function getApiHeaders(requireAuth = true, body = false) {
        const headers = {};
        const apiKey = document.getElementById('apiKey').value.trim();
        const tenantId = document.getElementById('tenantId').value.trim() || '1';
        if (apiKey) {
          headers['x-api-key'] = apiKey;
        }
        headers['x-tenant-id'] = tenantId;
        if (requireAuth && state.token) {
          headers['Authorization'] = `Bearer ${state.token}`;
        }
        if (body) {
          headers['Content-Type'] = 'application/json';
        }
        return headers;
      }

      async function requestJson(url, init = {}) {
        try {
          const resp = await fetch(url, init);
          const contentType = resp.headers.get('content-type') || '';
          const data = contentType.includes('application/json') ? await resp.json() : await resp.text();
          if (!resp.ok) {
            const errMsg = typeof data === 'string' ? data : (data?.detail || JSON.stringify(data));
            throw new Error(errMsg);
          }
          return { ok: true, data };
        } catch (err) {
          return { ok: false, error: err.message || String(err) };
        }
      }

      function clearLog() {
        document.getElementById('log').textContent = '已清空日志';
      }

      function loadSession() {
        const cached = localStorage.getItem(state.storageKey);
        if (cached && cached.length > 8) {
          setToken(cached);
          setStatus('authStatus', '已恢复上次会话');
        }
      }

      async function login() {
        setStatus('authStatus', '登录中...');
        const username = document.getElementById('username').value.trim();
        const password = document.getElementById('password').value.trim();
        const r = await requestJson('/api/v1/auth/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ username, password }),
        });
        if (!r.ok) {
          setStatus('authStatus', `登录失败：${r.error}`, true);
          appendLog(`登录失败: ${r.error}`);
          return;
        }
        setToken(r.data.access_token);
        localStorage.setItem(state.storageKey, r.data.access_token);
        setStatus('authStatus', `登录成功：${r.data.username} / ${r.data.role}`);
        appendLog(`登录成功: ${JSON.stringify(r.data)}`);
        await checkMe();
      }

      function logout() {
        setToken('');
        localStorage.removeItem(state.storageKey);
        setStatus('authStatus', '已退出');
        appendLog('已退出登录');
      }

      async function checkHealth() {
        const r = await requestJson('/health');
        setStatus('systemStatus', r.ok ? `health: ${JSON.stringify(r.data)}` : `health 失败：${r.error}`, !r.ok);
        appendLog(`health: ${JSON.stringify(r.ok ? r.data : r.error)}`);
      }

      async function checkMe() {
        const r = await requestJson('/api/v1/auth/me', {
          headers: getApiHeaders(true, false),
        });
        setStatus('authStatus', r.ok ? `鉴权通过：${r.data.username}（${r.data.role}）` : `鉴权失败：${r.error}`, !r.ok);
        appendLog(`auth/me: ${JSON.stringify(r.ok ? r.data : r.error)}`);
      }

      async function checkDashboard() {
        const r = await requestJson(state.dashboardPath, {
          headers: getApiHeaders(true, false),
        });
        setStatus('systemStatus', r.ok ? `工作台接口可访问：${state.dashboardPath}` : `工作台接口拒绝：${r.error}`, !r.ok);
        appendLog(`dashboard: ${JSON.stringify(r.ok ? r.data : r.error)}`);
      }

      async function createContact() {
        const payload = {
          phone: document.getElementById('contactPhone').value.trim(),
          name: document.getElementById('contactName').value.trim(),
          tags: document.getElementById('contactTags').value.trim(),
          consent_state: document.getElementById('contactConsent').value,
          dnc: false,
          timezone: 'Asia/Shanghai'
        };
        const r = await requestJson('/api/v1/contacts', {
          method: 'POST',
          headers: getApiHeaders(false, true),
          body: JSON.stringify(payload),
        });
        if (!r.ok) {
          setStatus('contactStatus', `新增联系人失败：${r.error}`, true);
          appendLog(`新增联系人失败: ${r.error}`);
          return;
        }
        window.__lastContactId = r.data.id;
        setStatus('contactStatus', `新增成功：联系人ID=${r.data.id}`);
        appendLog(`新增联系人: ${JSON.stringify(r.data)}`);
      }

      async function listContacts() {
        const r = await requestJson('/api/v1/contacts?page=1&size=20', {
          headers: getApiHeaders(false, false),
        });
        if (!r.ok) {
          setStatus('contactStatus', `查询联系人失败：${r.error}`, true);
          appendLog(`查询联系人失败: ${r.error}`);
          return;
        }
        setStatus('contactStatus', `查询成功：${(r.data || []).length} 条`);
        appendLog(`联系人列表: ${JSON.stringify((r.data || []).slice(0, 3))}`);
      }

      function parseContactIds() {
        const raw = document.getElementById('campaignContactIds').value || '';
        return raw
          .split(',')
          .map((v) => Number(v.trim()))
          .filter((v) => Number.isInteger(v) && v > 0);
      }

      async function createCampaign() {
        const payload = {
          name: document.getElementById('campaignName').value.trim(),
          script: document.getElementById('campaignScript').value.trim(),
          mode: document.getElementById('campaignMode').value,
          concurrency: 5,
          retry_limit: 1,
          retry_interval_sec: 30,
          attempt_interval_sec: 1200,
          recording_enabled: true,
          hangup_sms_enabled: true,
          contact_ids: parseContactIds(),
        };
        const r = await requestJson('/api/v1/campaigns', {
          method: 'POST',
          headers: getApiHeaders(false, true),
          body: JSON.stringify(payload),
        });
        if (!r.ok) {
          setStatus('operateStatus', `创建活动失败：${r.error}`, true);
          appendLog(`创建活动失败: ${r.error}`);
          return;
        }
        window.__lastCampaignId = r.data.id;
        setStatus('operateStatus', `创建活动成功：ID=${r.data.id}`);
        appendLog(`创建活动: ${JSON.stringify(r.data)}`);
      }

      async function startCampaign() {
        const campaignId = window.__lastCampaignId;
        const maxDials = Number(document.getElementById('campaignMaxDials').value) || 10;
        if (!campaignId) {
          setStatus('operateStatus', '请先创建活动', true);
          return;
        }
        const r = await requestJson(`/api/v1/campaigns/${campaignId}/start?max_dials=${maxDials}`, {
          method: 'POST',
          headers: getApiHeaders(false, false),
        });
        if (!r.ok) {
          setStatus('operateStatus', `启动活动失败：${r.error}`, true);
          appendLog(`启动活动失败: ${r.error}`);
          return;
        }
        setStatus('operateStatus', `活动启动成功：${r.data.auto_dial_count || r.data.call_ids?.length || 0} 条`);
        appendLog(`启动活动: ${JSON.stringify(r.data)}`);
      }

      async function createCall() {
        const payload = {
          phone: document.getElementById('callPhone').value.trim(),
          mode: document.getElementById('callMode').value,
          max_attempts: 1,
        };
        const r = await requestJson('/api/v1/calls', {
          method: 'POST',
          headers: getApiHeaders(false, true),
          body: JSON.stringify(payload),
        });
        if (!r.ok) {
          setStatus('operateStatus', `发起外呼失败：${r.error}`, true);
          appendLog(`发起外呼失败: ${r.error}`);
          return;
        }
        window.__lastCallId = r.data.id;
        setStatus('operateStatus', `外呼已发起：Call ID ${r.data.id}`);
        appendLog(`发起外呼: ${JSON.stringify(r.data)}`);
      }

      async function listCalls() {
        const r = await requestJson('/api/v1/calls?page=1&size=20', {
          headers: getApiHeaders(false, false),
        });
        if (!r.ok) {
          setStatus('operateStatus', `查询通话失败：${r.error}`, true);
          appendLog(`查询通话失败: ${r.error}`);
          return;
        }
        setStatus('operateStatus', `查询通话成功：${(r.data || []).length} 条`);
        appendLog(`通话列表: ${JSON.stringify((r.data || []).slice(0, 3))}`);
      }

      window.addEventListener('load', () => {
        loadSession();
        appendLog('页面加载完成，请先登录后执行业务链路测试。');
      });
    </script>
  </body>
</html>
"""
    return (
        page.replace("__TITLE__", page_title)
        .replace("__DEFAULT_USERNAME__", default_username)
        .replace("__DEFAULT_PASSWORD__", default_password)
        .replace("__DEFAULT_ROLE__", default_role)
        .replace("__DASHBOARD_PATH__", dashboard_path)
        .replace("__DEFAULT_API_KEY__", api_key)
    )


@router.get("/admin", response_class=HTMLResponse)
def admin_page():
    return _portal_page(
        page_title="管理员端",
        default_username=settings.demo_admin_username,
        default_password=settings.demo_admin_password,
        default_role="admin",
        dashboard_path="admin/dashboard",
        api_key=settings.api_key,
    )


@router.get("/agent", response_class=HTMLResponse)
def agent_page():
    return _portal_page(
        page_title="座席端",
        default_username=settings.demo_agent_username,
        default_password=settings.demo_agent_password,
        default_role="agent",
        dashboard_path="agent/dashboard",
        api_key=settings.api_key,
    )


@router.get("/docs.html")
def docs_page():
    return RedirectResponse(url="/docs")
