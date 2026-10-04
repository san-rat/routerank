import { useSyncExternalStore } from 'react'
import { api, ApiError, type Me } from '../api/client'

export type AccountState =
  | { kind: 'loading' }
  | { kind: 'signed-out' }
  | { kind: 'error' }
  | { kind: 'signed-in'; me: Me }

// A hint that this browser signed in, so pages can skip the API entirely for anonymous visitors
// (static-first: most visitors never call the API). The session cookie itself is HttpOnly.
const HINT = 'routerank.signedIn'

function readHint(): boolean {
  try {
    return localStorage.getItem(HINT) === '1'
  } catch {
    return false
  }
}

function writeHint(signedIn: boolean) {
  try {
    if (signedIn) localStorage.setItem(HINT, '1')
    else localStorage.removeItem(HINT)
  } catch {
    // storage blocked: the header just shows "Sign in" until /me is opened
  }
}

let state: AccountState = { kind: 'loading' }
let loading: Promise<void> | undefined
const listeners = new Set<() => void>()

function set(next: AccountState) {
  state = next
  if (next.kind === 'signed-in') writeHint(true)
  if (next.kind === 'signed-out') writeHint(false)
  listeners.forEach((listener) => listener())
}

/**
 * Loads the signed-in account. Without `verify`, calls the API only if this browser signed in
 * before; with it (the account page), always asks the API.
 */
export function loadAccount({ verify = false } = {}): Promise<void> {
  if (state.kind === 'signed-in' && !verify) return Promise.resolve()
  if (!verify && !readHint()) {
    set({ kind: 'signed-out' })
    return Promise.resolve()
  }
  loading ??= api
    .me()
    .then(
      (me) => set({ kind: 'signed-in', me }),
      (error) => set(error instanceof ApiError && error.status === 401 ? { kind: 'signed-out' } : { kind: 'error' }),
    )
    .finally(() => {
      loading = undefined
    })
  return loading
}

export function signedIn(me: Me) {
  set({ kind: 'signed-in', me })
}

export async function signOut() {
  await api.signOut()
  set({ kind: 'signed-out' })
}

export function useAccount(): AccountState {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    () => state,
  )
}

/** Test helper: forget everything */
export function resetAccount() {
  state = { kind: 'loading' }
  loading = undefined
  writeHint(false)
}
