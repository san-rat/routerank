import type { components } from './schema'

// Types come from the API's OpenAPI spec (openapi.json, checked by the backend's tests);
// regenerate schema.d.ts with `npm run api:types` after the spec changes.
export type Me = components['schemas']['Me']
type Nonce = components['schemas']['Nonce']
type GoogleSignIn = components['schemas']['GoogleSignIn']

export class ApiError extends Error {
  readonly status: number

  constructor(status: number) {
    super(`API request failed with status ${status}`)
    this.status = status
  }
}

/** Spring Security's CSRF token, readable by the page by design (the session cookie is not) */
function csrfToken(): string | undefined {
  const cookie = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))
  return cookie && decodeURIComponent(cookie.slice('XSRF-TOKEN='.length))
}

/**
 * Calls the API at the site's own /api, which the Pages Function (or Vite's proxy locally) forwards,
 * so cookies stay first-party and no CORS is needed.
 */
async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.method && init.method !== 'GET') {
    const token = csrfToken()
    if (token) headers.set('X-XSRF-TOKEN', token)
  }
  if (init.body) headers.set('Content-Type', 'application/json')
  const response = await fetch(`/api${path}`, { ...init, headers, credentials: 'same-origin' })
  if (!response.ok) throw new ApiError(response.status)
  return (response.status === 204 ? undefined : await response.json()) as T
}

export const api = {
  /** The signed-in account; rejects with status 401 when signed out */
  me: () => request<Me>('/me'),
  /** A one-time nonce for the next Google sign-in (also sets the CSRF cookie) */
  nonce: () => request<Nonce>('/auth/nonce'),
  signIn: (credential: string) =>
    request<Me>('/auth/google', {
      method: 'POST',
      body: JSON.stringify({ credential } satisfies GoogleSignIn),
    }),
  signOut: () => request<void>('/auth/logout', { method: 'POST' }),
}
