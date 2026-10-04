import { useSyncExternalStore } from 'react'
import { api, type LatLon, type Preview, type RouteView } from '../api/client'
import { waypointIndex } from './geometry'

/** The route being added or edited, kept across the add-route steps (and a page reload) */
export interface Draft {
  start?: LatLon
  end?: LatLon
  waypoints: LatLon[]
  /** What the start and end inputs show: a place name, or "Pin on map" */
  startLabel?: string
  endLabel?: string
  /** Points picked from search are snapped to the nearest main road without asking */
  fromSearch: { start?: boolean; end?: boolean }
  /** Editing (or replacing) one of the user's routes rather than adding one */
  editing?: { id: number; slot: number; name: string }
  /** The slot the user started from ("Add your #3 route"), preselected on the rank step */
  preferredSlot?: number
  preview?: Preview
  previewing: boolean
  previewFailed: boolean
  /** Earlier points, for undo */
  history: Points[]
}

type Points = Pick<Draft, 'start' | 'end' | 'waypoints' | 'startLabel' | 'endLabel'>

const STORAGE = 'routerank.draft'
const MAX_WAYPOINTS = 8
/** Routing runs when a drag ends, after this pause, never on every drag frame */
export const PREVIEW_DELAY_MS = 300

const EMPTY: Draft = { waypoints: [], fromSearch: {}, previewing: false, previewFailed: false, history: [] }

function restore(): Draft {
  try {
    const saved = sessionStorage.getItem(STORAGE)
    if (saved) return { ...EMPTY, ...(JSON.parse(saved) as Partial<Draft>), previewing: false, history: [] }
  } catch {
    // storage blocked or corrupt: start over
  }
  return EMPTY
}

let draft: Draft = restore()
const listeners = new Set<() => void>()
let timer: ReturnType<typeof setTimeout> | undefined
let inFlight: AbortController | undefined

function set(next: Partial<Draft>, replace = false) {
  // Replace (not merge) for a fresh draft, or the old start and end would survive
  draft = replace ? { ...EMPTY, ...next } : { ...draft, ...next }
  try {
    const { start, end, waypoints, startLabel, endLabel, editing, fromSearch, preferredSlot } = draft
    sessionStorage.setItem(
      STORAGE,
      JSON.stringify({ start, end, waypoints, startLabel, endLabel, editing, fromSearch, preferredSlot }),
    )
  } catch {
    // storage blocked: the draft lasts until the page closes
  }
  listeners.forEach((listener) => listener())
}

/** Changes the points (remembering the old ones for undo) and asks for a new preview */
function change(points: Partial<Points>, extra: Partial<Draft> = {}) {
  const { start, end, waypoints, startLabel, endLabel } = draft
  set({ ...extra, ...points, history: [...draft.history, { start, end, waypoints, startLabel, endLabel }].slice(-20) })
  schedulePreview()
}

function schedulePreview() {
  clearTimeout(timer)
  inFlight?.abort()
  if (!draft.start || !draft.end) {
    set({ preview: undefined, previewing: false })
    return
  }
  set({ previewing: true, previewFailed: false })
  timer = setTimeout(runPreview, PREVIEW_DELAY_MS)
}

async function runPreview() {
  const { start, end, waypoints, editing } = draft
  if (!start || !end) return
  const controller = new AbortController()
  inFlight = controller
  try {
    const preview = await api.preview({ start, end, waypoints, routeId: editing?.id }, controller.signal)
    if (controller.signal.aborted) return
    // A place picked from search may sit off the main road: use the nearest one without asking
    const side = preview.problems.find((p) => p.code === 'SIDE_ROAD' && p.nearest)
    if (side && (side.point === 'start' || side.point === 'end') && draft.fromSearch[side.point]) {
      set({ [side.point]: side.nearest, fromSearch: { ...draft.fromSearch, [side.point]: false } })
      return runPreview()
    }
    set({ preview, previewing: false })
  } catch {
    // Aborted means a newer preview replaced this one
    if (!controller.signal.aborted) set({ previewing: false, previewFailed: true })
  }
}

export const draftActions = {
  setStart(point: LatLon, label: string, fromSearch = false) {
    change({ start: point, startLabel: label }, { fromSearch: { ...draft.fromSearch, start: fromSearch } })
  },
  setEnd(point: LatLon, label: string, fromSearch = false) {
    change({ end: point, endLabel: label }, { fromSearch: { ...draft.fromSearch, end: fromSearch } })
  },
  clearEnd() {
    change({ end: undefined, endLabel: undefined, waypoints: [] })
  },
  swap() {
    change(
      { start: draft.end, end: draft.start, startLabel: draft.endLabel, endLabel: draft.startLabel, waypoints: [...draft.waypoints].reverse() },
      { fromSearch: { start: draft.fromSearch.end, end: draft.fromSearch.start } },
    )
  },
  /** A tap on the line: a waypoint in the right place among the others */
  addWaypoint(point: LatLon) {
    if (draft.waypoints.length >= MAX_WAYPOINTS) return false
    const line = draft.preview?.out ?? []
    const index = waypointIndex(line, draft.waypoints, point)
    change({ waypoints: [...draft.waypoints.slice(0, index), point, ...draft.waypoints.slice(index)] })
    return true
  },
  moveWaypoint(index: number, point: LatLon) {
    change({ waypoints: draft.waypoints.map((w, i) => (i === index ? point : w)) })
  },
  removeWaypoint(index: number) {
    change({ waypoints: draft.waypoints.filter((_, i) => i !== index) })
  },
  /** Dragging the start or end pin */
  movePoint(which: 'start' | 'end', point: LatLon) {
    change({ [which]: point, [`${which}Label`]: 'Pin on map' }, { fromSearch: { ...draft.fromSearch, [which]: false } })
  },
  /** "Use the nearest main road" on the side-road screen */
  useNearest(which: 'start' | 'end', point: LatLon) {
    change({ [which]: point })
  },
  undo() {
    const previous = draft.history.at(-1)
    if (!previous) return
    set({ ...previous, history: draft.history.slice(0, -1) })
    schedulePreview()
  },
  retry() {
    schedulePreview()
  },
  /** Edit (or replace) one of the user's routes: its points, in its slot */
  edit(route: Pick<RouteView, 'id' | 'slot' | 'name'>, points?: Pick<RouteView, 'start' | 'end' | 'waypoints'>) {
    const source = points ?? draft
    set(
      {
        start: source.start,
        end: source.end,
        waypoints: points ? points.waypoints : draft.waypoints,
        startLabel: points ? 'Start' : draft.startLabel,
        endLabel: points ? 'End' : draft.endLabel,
        editing: { id: route.id, slot: route.slot, name: route.name },
      },
      true,
    )
    schedulePreview()
  },
  /** A fresh draft, optionally aimed at a slot */
  reset(preferredSlot?: number) {
    clearTimeout(timer)
    inFlight?.abort()
    set({ preferredSlot }, true)
  },
}

/** Picks up a restored draft's preview after a reload */
export function ensurePreview() {
  if (draft.start && draft.end && !draft.preview && !draft.previewing) schedulePreview()
}

export function getDraft(): Draft {
  return draft
}

export function useDraft(): Draft {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    () => draft,
  )
}
