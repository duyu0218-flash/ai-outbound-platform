import { reactive } from 'vue';
import { apiRequest } from './api';
const KEY = 'ai-platform-access-token';
export const session = reactive({
  token: sessionStorage.getItem(KEY),
  user: null,
  loading: false,
  epoch: 0,
});
let initialization;
export async function initialize() {
  if (initialization) return initialization;
  initialization = (async () => {
    if (!session.token) return;
    session.loading = true;
    try {
      session.user = await apiRequest('/api/v1/auth/me', {}, session.token);
    } catch {
      clearSession();
    } finally {
      session.loading = false;
    }
  })();
  return initialization;
}
export function clearSession() {
  session.epoch++;
  session.token = null;
  session.user = null;
  sessionStorage.removeItem(KEY);
}
export async function login(username, password, role, code = '', apiKey = '') {
  const response = apiKey
    ? await apiRequest(
        '/api/v1/auth/key-session',
        { method: 'POST', body: JSON.stringify({ code }) },
        apiKey,
      )
    : await apiRequest('/api/v1/auth/login', {
        method: 'POST',
        body: JSON.stringify({ username, password, code }),
      });
  if (
    response.role !== role &&
    !(role === 'agent' && ['admin', 'operator'].includes(response.role))
  )
    throw new Error(
      role === 'admin'
        ? 'This account is not an administrator'
        : 'This account cannot access the agent portal',
    );
  const profile = await apiRequest('/api/v1/auth/me', {}, response.access_token);
  sessionStorage.setItem(KEY, response.access_token);
  session.user = profile;
  session.token = response.access_token;
  session.epoch++;
  return profile;
}
export async function logout() {
  try {
    if (session.token) await apiRequest('/api/v1/auth/logout', { method: 'POST' }, session.token);
  } finally {
    clearSession();
  }
}
export function request(path, method = 'GET', body, options = {}) {
  return apiRequest(
    path,
    {
      method,
      ...options,
      ...(body === undefined
        ? {}
        : { body: body instanceof FormData ? body : JSON.stringify(body) }),
    },
    session.token,
  );
}
export async function download(path, name, options = {}) {
  let verification = '';
  if (/\/export(?:\?|$)/.test(path)) {
    const security = await request('/api/v1/auth/security');
    if (security.config.export_verification) {
      const { ElMessageBox } = await import('element-plus');
      try {
        verification = (
          await ElMessageBox.prompt('请输入验证器中的六位验证码', '导出验证', {
            inputPattern: /^[0-9]{6}$/,
          })
        ).value;
      } catch {
        return;
      }
    }
  }
  const response = await fetch(path, {
    method: options.method || 'GET',
    ...(options.body === undefined ? {} : { body: JSON.stringify(options.body) }),
    headers: {
      Authorization: 'Bearer ' + session.token,
      ...(verification ? { 'X-Export-Verification': verification } : {}),
      ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
  });
  if (!response.ok) {
    let body = await response.json().catch(() => ({}));
    throw new Error(body.message || body.detail || `HTTP ${response.status}`);
  }
  const blob = await response.blob(),
    link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download =
    name ||
    response.headers.get('content-disposition')?.match(/filename="?([^";]+)"?/i)?.[1] ||
    'export.csv';
  link.click();
  URL.revokeObjectURL(link.href);
}
