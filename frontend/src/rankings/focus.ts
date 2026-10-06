import { useSyncExternalStore } from 'react'

/** The stretch on show (/s/:slug): the map switches to its province, zooms to it and highlights it */
export interface Focus {
  slug: string
  /** Province slug */
  province: string
  bbox: [number, number, number, number]
  /** The whole road it is part of, its stretches' slugs: drawn together with it */
  road?: string[]
}

let focus: Focus | null = null
const listeners = new Set<() => void>()

export function setFocus(next: Focus | null) {
  if (next?.slug === focus?.slug && next?.province === focus?.province && next?.road?.join() === focus?.road?.join()) {
    return
  }
  focus = next
  listeners.forEach((l) => l())
}

export function useFocus(): Focus | null {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => focus,
  )
}
