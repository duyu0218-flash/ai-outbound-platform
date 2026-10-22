export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}
export async function apiRequest(path, options = {}, token) {
  const headers = new Headers(options.headers);
  if (options.body && !headers.has('Content-Type') && !(options.body instanceof FormData))
    headers.set('Content-Type', 'application/json');
  if (token) headers.set('Authorization', 'Bearer ' + token);
  const controller = new AbortController(),
    timeout = options.timeoutMs ?? 20000,
    timer = setTimeout(() => controller.abort(), timeout),
    abort = () => controller.abort();
  options.signal?.addEventListener('abort', abort, { once: true });
  let response;
  try {
    response = await fetch(path, { ...options, headers, signal: controller.signal });
  } catch (error) {
    if (controller.signal.aborted && !options.signal?.aborted)
      throw new ApiError(`Request timed out after ${Math.ceil(timeout / 1000)} seconds`, 504);
    throw error;
  } finally {
    clearTimeout(timer);
    options.signal?.removeEventListener('abort', abort);
  }
  const body = (response.headers.get('content-type') || '').includes('application/json')
    ? await response.json()
    : await response.text();
  if (!response.ok)
    throw new ApiError(
      typeof body === 'object' && body
        ? body.message || body.detail || body.error || `HTTP ${response.status}`
        : String(body || `HTTP ${response.status}`),
      response.status,
    );
  return body;
}
export function formatDate(value) {
  if (!value) return '-';
  return new Intl.DateTimeFormat(undefined, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(
    new Date(
      typeof value === 'number'
        ? value
        : /(?:Z|[+-]\d{2}:?\d{2})$/.test(value)
          ? value
          : value + 'Z',
    ),
  );
}
export function numberList(raw) {
  return String(raw)
    .split(/[,\s\r\n]+/)
    .map(Number)
    .filter((n) => Number.isInteger(n) && n > 0);
}
