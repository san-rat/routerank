import { useSyncExternalStore } from 'react'
import { type Buses, type BusRoute, useFile, useManifest } from '../rankings/data'

// The "Bus routes" chip turns the red layer on and off; remembered in this browser only
const SHOWN = 'routerank.busesShown'
let shown = (() => {
  try {
    return localStorage.getItem(SHOWN) !== '0'
  } catch {
    return true
  }
})()
const listeners = new Set<() => void>()

export function setShown(next: boolean) {
  shown = next
  try {
    localStorage.setItem(SHOWN, next ? '1' : '0')
  } catch {
    // storage blocked: lasts until the page closes
  }
  listeners.forEach((l) => l())
}

export function useShown(): boolean {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => shown,
  )
}

/** The published bus routes: undefined while loading, [] when there are none */
export function useBusRoutes(): BusRoute[] | undefined {
  const manifest = useManifest()
  const key = manifest.kind === 'loaded' ? manifest.manifest.buses : undefined
  const file = useFile<Buses>(key)
  if (manifest.kind === 'none' || manifest.kind === 'error' || (manifest.kind === 'loaded' && !key)) return []
  return file === null ? [] : file?.routes
}
