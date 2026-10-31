import { reactive } from 'vue';
import { apiRequest } from '../api';
// Separate storage from the staff token: the two principals never share a session.
const KEY = 'ai-platform-client-token';
export const clientSession = reactive({
  token: sessionStorage.getItem(KEY),
  user: null,
  epoch: 0,
});
let initialization;
export function initializeClient() {
  if (initialization) return initialization;
  initialization = (async () => {
    if (!clientSession.token) return;
    try {
      clientSession.user = await apiRequest('/api/v1/client/me', {}, clientSession.token);
    } catch {
      clearClient();
    }
  })();
  return initialization;
}
function store(token) {
  clientSession.token = token;
  sessionStorage.setItem(KEY, token);
}
export function clearClient() {
  clientSession.epoch++;
  clientSession.token = null;
  clientSession.user = null;
  initialization = null;
  sessionStorage.removeItem(KEY);
}
export async function clientLogin(username, password) {
  const response = await apiRequest('/api/v1/client/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  });
  store(response.access_token);
  clientSession.user = await apiRequest('/api/v1/client/me', {}, response.access_token);
  clientSession.epoch++;
  return clientSession.user;
}
export async function refreshClient() {
  clientSession.user = await apiRequest('/api/v1/client/me', {}, clientSession.token);
}
export async function changePassword(oldPassword, newPassword) {
  const response = await clientRequest('/api/v1/client/auth/password', 'POST', {
    old_password: oldPassword,
    new_password: newPassword,
  });
  store(response.access_token);
  await refreshClient();
}
export async function clientLogout() {
  try {
    if (clientSession.token) await clientRequest('/api/v1/client/auth/logout', 'POST');
  } finally {
    clearClient();
  }
}
export async function clientRequest(path, method = 'GET', body) {
  try {
    return await apiRequest(
      path,
      { method, ...(body === undefined ? {} : { body: JSON.stringify(body) }) },
      clientSession.token,
    );
  } catch (error) {
    if (error.status === 401) {
      clearClient();
      window.location.assign('/client/login');
    }
    throw error;
  }
}
export async function clientDownload(path, name) {
  const response = await fetch(path, {
    headers: { Authorization: 'Bearer ' + clientSession.token },
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.detail || `HTTP ${response.status}`);
  }
  const link = document.createElement('a');
  link.href = URL.createObjectURL(await response.blob());
  link.download = name;
  link.click();
  URL.revokeObjectURL(link.href);
}
