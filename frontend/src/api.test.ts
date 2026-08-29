import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiError, apiRequest, formatDate } from './api'

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('apiRequest', () => {
  it('adds JSON and bearer headers and returns JSON', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(
      JSON.stringify({ ok: true }),
      { status: 200, headers: { 'content-type': 'application/json' } },
    ))

    await expect(apiRequest('/api/test', { method: 'POST', body: JSON.stringify({ value: 1 }) }, 'token-1'))
      .resolves.toEqual({ ok: true })

    const [, options] = fetchMock.mock.calls[0]
    const headers = new Headers(options?.headers)
    expect(headers.get('Authorization')).toBe('Bearer token-1')
    expect(headers.get('Content-Type')).toBe('application/json')
  })

  it('normalizes backend errors', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(
      JSON.stringify({ message: 'denied' }),
      { status: 403, headers: { 'content-type': 'application/json' } },
    ))

    await expect(apiRequest('/api/denied')).rejects.toEqual(new ApiError('denied', 403))
  })

  it('returns text and falls back to an HTTP status message', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response('ready', { status: 200, headers: { 'content-type': 'text/plain' } }))
      .mockResolvedValueOnce(new Response('', { status: 502, headers: { 'content-type': 'text/plain' } }))

    await expect(apiRequest('/ready')).resolves.toBe('ready')
    await expect(apiRequest('/broken')).rejects.toEqual(new ApiError('HTTP 502', 502))
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('preserves an externally requested abort', async () => {
    const controller = new AbortController()
    vi.spyOn(globalThis, 'fetch').mockImplementation((_path, options) => new Promise((_resolve, reject) => {
      options?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    }))

    const request = apiRequest('/api/cancelled', { signal: controller.signal })
    const expectation = expect(request).rejects.toMatchObject({ name: 'AbortError' })
    controller.abort()
    await expectation
  })

  it('maps its own timeout to HTTP 504', async () => {
    vi.useFakeTimers()
    vi.spyOn(globalThis, 'fetch').mockImplementation((_path, options) => new Promise((_resolve, reject) => {
      options?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    }))

    const request = apiRequest('/api/slow')
    const expectation = expect(request).rejects.toEqual(new ApiError('Request timed out after 20 seconds', 504))
    await vi.advanceTimersByTimeAsync(20_001)
    await expectation
  })
})

describe('formatDate', () => {
  it('handles empty and UTC values', () => {
    expect(formatDate()).toBe('-')
    expect(formatDate('2026-08-29T10:00:00')).not.toBe('-')
    expect(formatDate('2026-08-29T10:00:00Z')).not.toBe('-')
  })
})
