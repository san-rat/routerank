import { useSyncExternalStore } from 'react'
import { api, ApiError, type MyRoutes, type RouteView, type SlotView } from '../api/client'

export type MyRoutesState =
  | { kind: 'loading' }
  | { kind: 'signed-out' }
  | { kind: 'error' }
  | { kind: 'loaded'; data: MyRoutes }

export const SLOTS = [1, 2, 3] as const
export const POINTS: Record<number, number> = { 1: 3, 2: 2, 3: 1 }

let state: MyRoutesState = { kind: 'loading' }
const listeners = new Set<() => void>()

function set(next: MyRoutesState) {
  state = next
  listeners.forEach((listener) => listener())
}

/** Loads (or reloads) the signed-in user's routes and slot cooldowns */
export async function loadMyRoutes(): Promise<void> {
  try {
    set({ kind: 'loaded', data: await api.myRoutes() })
  } catch (error) {
    set(error instanceof ApiError && error.status === 401 ? { kind: 'signed-out' } : { kind: 'error' })
  }
}

export function setMyRoutes(data: MyRoutes) {
  set({ kind: 'loaded', data })
}

export function useMyRoutes(): MyRoutesState {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    () => state,
  )
}

/** Test helper */
export function resetMyRoutes() {
  state = { kind: 'loading' }
}

/**
 * Where the user's routes end up when one (or a new one, id null) goes into a slot, as the API does it: taking
 * an occupied slot pushes routes down into the nearest free slot below, or up into the free slot above if there
 * is none below. Returns route id → slot, or null when all three slots hold other routes.
 */
export function placeInSlot(routes: Pick<RouteView, 'id' | 'slot'>[], moving: number | null, target: number) {
  const bySlot: (number | null)[] = [null, null, null, null]
  for (const r of routes) if (r.id !== moving) bySlot[r.slot] = r.id
  if (bySlot[target] !== null) {
    let free = SLOTS.find((s) => s > target && bySlot[s] === null)
    if (free) {
      for (let s = free; s > target; s--) bySlot[s] = bySlot[s - 1]
    } else {
      free = [...SLOTS].reverse().find((s) => s < target && bySlot[s] === null)
      if (!free) return null
      for (let s = free; s < target; s++) bySlot[s] = bySlot[s + 1]
    }
  }
  bySlot[target] = moving ?? -1
  const slots = new Map<number, number>()
  SLOTS.forEach((s) => bySlot[s] !== null && slots.set(bySlot[s]!, s))
  return slots
}

/** When a slot can change again, or null when it can change now (free or in its 15-minute grace window) */
export function lockedUntil(slot: SlotView | undefined, now = Date.now()): Date | null {
  if (!slot?.lockedUntil) return null
  if (slot.graceUntil && new Date(slot.graceUntil).getTime() > now) return null
  const until = new Date(slot.lockedUntil)
  return until.getTime() > now ? until : null
}

/** "17 h 42 min" */
export function formatWait(until: Date, now = Date.now()): { hours: number; minutes: number; text: string } {
  const total = Math.max(0, Math.ceil((until.getTime() - now) / 60000))
  const hours = Math.floor(total / 60)
  const minutes = total % 60
  return { hours, minutes, text: hours ? `${hours} h ${minutes} min` : `${minutes} min` }
}
