// @vitest-environment node
// The Pages Function in functions/api (kept out of functions/ so it isn't deployed as a route)
import { afterEach, describe, expect, it, vi } from 'vitest'
import { onRequest } from '../../functions/api/[[path]]'

const env = { API_ORIGIN: 'https://api.example.test', PROXY_SECRET: 's3cret' }

function forwarded() {
  const fetch = vi.fn(async (_url: URL, _init?: RequestInit) => new Response('ok'))
  vi.stubGlobal('fetch', fetch)
  return fetch
}

afterEach(() => vi.unstubAllGlobals())

describe('API proxy function', () => {
  it('forwards the path, cookies and Origin with the shared secret', async () => {
    const fetch = forwarded()
    const request = new Request('https://routerank.pages.dev/api/me?x=1', {
      headers: { Cookie: '__Host-session=abc', Origin: 'https://routerank.pages.dev', 'X-XSRF-TOKEN': 't' },
    })
    await onRequest({ request, env })

    const [url, init] = fetch.mock.calls[0]
    expect(url.toString()).toBe('https://api.example.test/api/me?x=1')
    const headers = new Headers(init!.headers)
    expect(headers.get('X-RouteRank-Proxy-Secret')).toBe('s3cret')
    expect(headers.get('Cookie')).toBe('__Host-session=abc')
    expect(headers.get('Origin')).toBe('https://routerank.pages.dev')
    expect(headers.get('X-XSRF-TOKEN')).toBe('t')
    expect(init!.redirect).toBe('manual')
  })

  it("sends the visitor's IP from CF-Connecting-IP, never their own X-Forwarded-For", async () => {
    const fetch = forwarded()
    const request = new Request('https://routerank.pages.dev/api/me', {
      headers: { 'CF-Connecting-IP': '203.0.113.7', 'X-Forwarded-For': '10.0.0.1' },
    })
    await onRequest({ request, env })
    expect(new Headers(fetch.mock.calls[0][1]!.headers).get('X-Forwarded-For')).toBe('203.0.113.7')

    await onRequest({ request: new Request('https://routerank.pages.dev/api/me', { headers: { 'X-Forwarded-For': '10.0.0.1' } }), env })
    expect(new Headers(fetch.mock.calls[1][1]!.headers).has('X-Forwarded-For')).toBe(false)
  })

  it('forwards the body of requests that change data', async () => {
    const fetch = forwarded()
    const request = new Request('https://routerank.pages.dev/api/auth/google', { method: 'POST', body: '{"credential":"x"}' })
    await onRequest({ request, env })
    expect(fetch.mock.calls[0][1]!.method).toBe('POST')
    expect(fetch.mock.calls[0][1]!.body).toBeTruthy()
  })

  it('answers 503 when the API is not configured', async () => {
    const fetch = forwarded()
    const response = await onRequest({ request: new Request('https://routerank.pages.dev/api/me'), env: {} })
    expect(response.status).toBe(503)
    expect(fetch).not.toHaveBeenCalled()
  })
})
