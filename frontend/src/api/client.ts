import type { components } from './schema'

// Types come from the API's OpenAPI spec (openapi.json, checked by the backend's tests);
// regenerate schema.d.ts with `npm run api:types` after the spec changes.
export type Me = components['schemas']['Me']
type Nonce = components['schemas']['Nonce']
type GoogleSignIn = components['schemas']['GoogleSignIn']
export type LatLon = components['schemas']['LatLon']
export type RouteInput = components['schemas']['RouteInput']
export type Preview = components['schemas']['Preview']
export type Problem = components['schemas']['Problem']
export type RouteView = components['schemas']['RouteView']
export type MyRoutes = components['schemas']['MyRoutes']
export type SlotView = components['schemas']['SlotView']
export type Place = components['schemas']['Place']
// The 422 body (from the API's exception handler, so not in the spec)
type Refused = { problems: Problem[] }
type Order = components['schemas']['Order']

export class ApiError extends Error {
  readonly status: number
  /** Why a save was refused (422): the rules it breaks */
  readonly problems: Problem[]

  constructor(status: number, problems: Problem[] = []) {
    super(`API request failed with status ${status}`)
    this.status = status
    this.problems = problems
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
  if (!response.ok) {
    const refused = response.status === 422 ? ((await response.json().catch(() => null)) as Refused | null) : null
    throw new ApiError(response.status, refused?.problems ?? [])
  }
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

  myRoutes: () => request<MyRoutes>('/routes'),
  /** Works a route out from its points: both directions, length, name and any problems */
  preview: (input: RouteInput, signal?: AbortSignal) =>
    request<Preview>('/routes/preview', { method: 'POST', body: JSON.stringify(input), signal }),
  /** Rejects with status 422 and the problems when a rule blocks it */
  createRoute: (input: RouteInput) => request<RouteView>('/routes', { method: 'POST', body: JSON.stringify(input) }),
  updateRoute: (id: number, input: RouteInput) =>
    request<RouteView>(`/routes/${id}`, { method: 'PUT', body: JSON.stringify(input) }),
  reorderRoutes: (routes: Order['routes']) =>
    request<MyRoutes>('/routes/order', { method: 'PUT', body: JSON.stringify({ routes } satisfies Order) }),
  removeRoute: (id: number) => request<void>(`/routes/${id}`, { method: 'DELETE' }),
  searchPlaces: (q: string, signal?: AbortSignal) =>
    request<Place[]>(`/places?q=${encodeURIComponent(q)}`, { signal }),
}
