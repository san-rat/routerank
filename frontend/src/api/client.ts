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
export type BusCheck = components['schemas']['BusCheck']
// Admin pages (plain pages, no Figma design)
export type BusRouteInput = components['schemas']['BusRouteInput']
export type BusRouteView = components['schemas']['BusRouteView']
export type Drawing = components['schemas']['DrawingView']
export type Cluster = components['schemas']['ClusterView']
export type FlaggedAccount = components['schemas']['AccountView']
export type RouteSummary = components['schemas']['RouteSummary']
export type AccountRow = components['schemas']['AccountRow']
export type AccountDetail = components['schemas']['AccountDetail']
export type AuditEntry = components['schemas']['Entry']
export type ScoringRun = components['schemas']['Run']
type Resolved = components['schemas']['Resolved']
// The 422 body (from the API's exception handler, so not in the spec)
type Refused = { problems: Problem[] }
type Order = components['schemas']['Order']

export class ApiError extends Error {
  readonly status: number
  /** Why a save was refused (422): the rules it breaks */
  readonly problems: Problem[]
  /** The API's reason code, when it gives one: bot_check_failed, bot_check_unavailable, fresh_sign_in */
  readonly code?: string

  constructor(status: number, problems: Problem[] = [], code?: string) {
    super(`API request failed with status ${status}`)
    this.status = status
    this.problems = problems
    this.code = code
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
    const body = (await response.json().catch(() => null)) as (Partial<Refused> & { code?: string }) | null
    throw new ApiError(response.status, response.status === 422 ? (body?.problems ?? []) : [], body?.code)
  }
  return (response.status === 204 ? undefined : await response.json()) as T
}

export const api = {
  /** The signed-in account; rejects with status 401 when signed out */
  me: () => request<Me>('/me'),
  /** A one-time nonce for the next Google sign-in (also sets the CSRF cookie) */
  nonce: () => request<Nonce>('/auth/nonce'),
  /** With a Turnstile token for "signin" and the device signal, when the site has them */
  signIn: (credential: string, turnstile?: string, device?: string) =>
    request<Me>('/auth/google', {
      method: 'POST',
      body: JSON.stringify({ credential, turnstile, device } satisfies GoogleSignIn),
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

const post = (body: unknown) => ({ method: 'POST', body: JSON.stringify(body) })

/** The admin API. Every call needs the admin role and a fresh sign-in (401 with code fresh_sign_in otherwise). */
export const adminApi = {
  session: () => request<{ adminId: number }>('/admin/session'),
  queue: () => request<Cluster[]>('/admin/queue'),
  held: () => request<FlaggedAccount[]>('/admin/held'),
  releaseCluster: (cluster: string, reason: string) => request<Resolved>('/admin/clusters/release', post({ cluster, reason })),
  removeCluster: (cluster: string, reason: string) => request<Resolved>('/admin/clusters/remove', post({ cluster, reason })),
  findAccounts: (q: string) => request<AccountRow[]>(`/admin/accounts?q=${encodeURIComponent(q)}`),
  account: (id: number) => request<AccountDetail>(`/admin/accounts/${id}`),
  releaseAccount: (id: number, reason: string) => request<void>(`/admin/accounts/${id}/release`, post({ reason })),
  ban: (id: number, reason: string) => request<void>(`/admin/accounts/${id}/ban`, post({ reason })),
  unban: (id: number, reason: string) => request<void>(`/admin/accounts/${id}/unban`, post({ reason })),
  removeRoute: (id: number, reason: string) => request<void>(`/admin/routes/${id}/remove`, post({ reason })),
  restoreRoute: (id: number, reason: string) => request<void>(`/admin/routes/${id}/restore`, post({ reason })),
  audit: (before?: number) => request<AuditEntry[]>(`/admin/audit${before ? `?before=${before}` : ''}`),
  runScoring: (reason: string) => request<ScoringRun>('/admin/scoring/run', post({ reason })),
  busRoutes: () => request<BusRouteView[]>('/admin/bus-routes'),
  drawBusRoute: (input: BusRouteInput, signal?: AbortSignal) =>
    request<Drawing>('/admin/bus-routes/draw', { ...post(input), signal }),
  addBusRoute: (input: BusRouteInput) => request<{ id: number }>('/admin/bus-routes', post(input)),
  redrawBusRoute: (id: number, input: BusRouteInput) =>
    request<void>(`/admin/bus-routes/${id}`, { method: 'PUT', body: JSON.stringify(input) }),
  retireBusRoute: (id: number, reason: string) => request<void>(`/admin/bus-routes/${id}/retire`, post({ reason })),
}
