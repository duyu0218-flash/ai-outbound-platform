import { createHash, createHmac, randomBytes } from 'node:crypto';
const escape = (value) => encodeURIComponent(value).replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase());
export function canonicalQuery(query) {
  const pairs = [...new URLSearchParams(query)];
  if (new Set(pairs.map(([key]) => key)).size !== pairs.length) throw new Error('Duplicate query key');
  return pairs.sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([key, value]) => escape(key) + '=' + escape(value)).join('&');
}
export function signedRequest({ applicationId, secret, method, path, query = '', body, idempotencyKey = '', timestamp = String(Math.floor(Date.now() / 1000)), nonce = randomBytes(16).toString('hex') }) {
  if (!/^\/openapi\/v1\/[A-Za-z0-9_/-]+$/.test(path) || path.includes('//')) throw new Error('Invalid API path');
  if (method !== 'GET' && !idempotencyKey) throw new Error('Stable idempotency key required for writes');
  const bytes = body === undefined ? '' : JSON.stringify(body);
  const digest = createHash('sha256').update(bytes).digest('hex');
  const canonical = [method, path, canonicalQuery(query), timestamp, nonce, digest, idempotencyKey, ''].join('\n');
  const signature = createHmac('sha256', secret).update(canonical).digest('hex');
  return { method, headers: { Authorization: `HMAC-SHA256 Credential=${applicationId},Signature=${signature}`, 'Content-Type': 'application/json', 'X-Timestamp': timestamp, 'X-Nonce': nonce, 'X-Content-SHA256': digest, ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}) }, ...(body === undefined ? {} : { body: bytes }) };
}
// No automatic retry. For an uncertain POST, query by the original idempotency key by repeating
// exactly the same body with a fresh nonce/signature. Never regenerate an expiry or business key.
export async function sendMessage({ baseUrl, applicationId, secret, idempotencyKey, message }) {
  const base = new URL(baseUrl);
  if (base.protocol !== 'https:' && !(base.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(base.hostname))) throw new Error('HTTPS required');
  return fetch(new URL('/openapi/v1/messages', base), { ...signedRequest({ applicationId, secret, method: 'POST', path: '/openapi/v1/messages', body: message, idempotencyKey }), redirect: 'error', signal: AbortSignal.timeout(10000) });
}
