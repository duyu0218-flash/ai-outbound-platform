export class ApiError extends Error {
  status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export async function apiRequest<T>(path: string, options: RequestInit = {}, token?: string | null): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const timeoutController = new AbortController()
  const timeoutId = window.setTimeout(() => timeoutController.abort(), 20_000)
  const externalSignal = options.signal
  const abortFromExternal = () => timeoutController.abort()
  externalSignal?.addEventListener('abort', abortFromExternal, { once: true })
  let response: Response
  try {
    response = await fetch(path, { ...options, headers, signal: timeoutController.signal })
  } catch (error) {
    if (timeoutController.signal.aborted && !externalSignal?.aborted) {
      throw new ApiError('Request timed out after 20 seconds', 504)
    }
    throw error
  } finally {
    window.clearTimeout(timeoutId)
    externalSignal?.removeEventListener('abort', abortFromExternal)
  }
  const contentType = response.headers.get('content-type') || ''
  const body = contentType.includes('application/json') ? await response.json() : await response.text()

  if (!response.ok) {
    const message = typeof body === 'object' && body
      ? body.message || body.detail || body.error || `HTTP ${response.status}`
      : String(body || `HTTP ${response.status}`)
    throw new ApiError(message, response.status)
  }
  return body as T
}

export function formatDate(value?: string): string {
  if (!value) return '-'
  return new Intl.DateTimeFormat(undefined, {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value.endsWith('Z') ? value : `${value}Z`))
}
