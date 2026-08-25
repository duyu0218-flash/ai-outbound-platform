from fastapi import APIRouter
from fastapi.responses import HTMLResponse, RedirectResponse

from ...config import get_settings

settings = get_settings()

router = APIRouter(tags=["pages"])


def _demo_page(role: str, default_username: str, default_password: str, default_role: str) -> str:
    return f"""\
<!doctype html>
<html lang=\"zh-CN\">
  <head>
    <meta charset=\"utf-8\"/>
    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />
    <title>AI 外呼平台 · {role}测试页</title>
    <style>
      body {{ font-family: Arial, sans-serif; padding: 32px; }}
      .box {{ max-width: 680px; border:1px solid #ddd; border-radius: 8px; padding: 20px; }}
      .row {{ margin: 8px 0; }}
      label {{ display:inline-block; width: 100px; }}
      input {{ width: 250px; padding: 6px; }}
      pre {{ white-space: pre-wrap; background: #f7f7f7; border: 1px solid #e5e5e5; padding: 10px; }}
      .ok {{ color: #1e8e3e; }}
      .err {{ color: #d22; }}
    </style>
  </head>
  <body>
    <div class=\"box\">
      <h2>AI 外呼平台 · {role}测试页</h2>
      <p>默认测试账号：{default_username} / {default_password}</p>
      <p>角色：{default_role}</p>
      <form id=\"f\">
        <div class=\"row\"><label>账号</label><input id=\"u\" value=\"{default_username}\"/></div>
        <div class=\"row\"><label>密码</label><input id=\"p\" type=\"password\" value=\"{default_password}\"/></div>
        <div class=\"row\">
          <button type=\"button\" onclick=\"login()\">登录</button>
          <button type=\"button\" onclick=\"clearAll()\">清空日志</button>
        </div>
      </form>
      <pre id=\"out\"></pre>
    </div>
    <script>
      async function login() {{
        const username = document.getElementById('u').value;
        const password = document.getElementById('p').value;
        document.getElementById('out').className = '';
        document.getElementById('out').textContent = '正在登录...';
        try {{
          const r = await fetch('/api/v1/auth/login', {{
            method:'POST',
            headers: {{'Content-Type':'application/json'}},
            body: JSON.stringify({{username, password}})
          }});
          const resp = await r.json();
          if (!r.ok) {{
            throw new Error(resp.detail || '登录失败');
          }}
          const token = resp.access_token;
          const meR = await fetch('/api/v1/auth/me', {{
            headers: {{ Authorization: 'Bearer ' + token }}
          }});
          const me = await meR.json();
          if (!meR.ok) {{
            throw new Error(me.detail || '登录后查询 me 失败');
          }}
          document.getElementById('out').className = 'ok';
          document.getElementById('out').textContent = JSON.stringify({{
            login: resp,
            me,
          }}, null, 2);
        }} catch (e) {{
          document.getElementById('out').className = 'err';
          document.getElementById('out').textContent = e.message || String(e);
        }}
      }}

      function clearAll() {{
        document.getElementById('out').textContent = '';
        document.getElementById('out').className = '';
      }}
    </script>
  </body>
</html>
"""


@router.get("/admin", response_class=HTMLResponse)
def admin_page():
    return _demo_page(
        role="管理员端",
        default_username=settings.demo_admin_username,
        default_password=settings.demo_admin_password,
        default_role="admin",
    )


@router.get("/agent", response_class=HTMLResponse)
def agent_page():
    return _demo_page(
        role="座席端",
        default_username=settings.demo_agent_username,
        default_password=settings.demo_agent_password,
        default_role="agent",
    )


@router.get("/docs.html")
def docs_page():
    return RedirectResponse(url="/docs")
