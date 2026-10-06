import type { FeatureCollection, MultiLineString } from 'geojson'
import { useEffect, useState, useSyncExternalStore } from 'react'

// The rankings the scoring job publishes to R2 every 30 minutes (backend scoring/Publication.java). Anonymous
// visitors read only these files, never the API. Every file but the manifest is named by its content hash, so
// it never changes and is fetched once; the manifest names the current ones.

/** Where the files are: the routerank-data bucket's public address, or /rankings in development */
const dataUrl = () => (import.meta.env.VITE_DATA_URL ?? '').replace(/\/$/, '')

export const PAGE_SIZE = 20

/** Heatmap colours, few points to most (Figma heat/1–5) */
export const HEAT = ['#a6ddd0', '#5fc2ae', '#249c8b', '#127d72', '#0a524d']

export interface ProvinceEntry {
  name: string
  /** Every stretch with points, ranked or not; 0 = no votes yet (W08) */
  stretches: number
  ranked: number
  people: number
  heat: string | null
  details: string | null
  pages: string[]
}

export interface Manifest {
  version: number
  generatedAt: string
  pageSize: number
  /** Points thresholds between the five colours, ascending (fewer when scores are few) */
  bands: number[]
  overall: { ranked: number; pages: string[] }
  provinces: Record<string, ProvinceEntry>
  slugs: string
  /** The bus routes file; absent in files published before Phase 6 */
  buses?: string
}

export interface Detail {
  name: string
  /** The road's name or number */
  road: string | null
  province: string
  points: number
  people: number
  /** #1, #2 and #3 votes */
  votes: [number, number, number]
  lengthM: number
  rankOverall: number | null
  rankProvince: number | null
  /** [lon, lat] of its two ends, the one nearer Colombo first */
  ends: [[number, number], [number, number]]
  bbox: [number, number, number, number]
  /** The bus route most of its points come from extensions of ("Extends 99") */
  extendsBus?: string
  /** The whole road it is part of: a key in Details.roads */
  along?: string
}

/** Stretches joined end to end on a road with the same name, shown together on the map; display only */
export interface Road {
  name: string
  lengthM: number
  /** Their slugs, from the end nearer Colombo */
  stretches: string[]
  bbox: [number, number, number, number]
}

export interface Entry {
  rank: number
  slug: string
  name: string
  /** Province slug */
  province: string
  points: number
  people: number
  lengthM: number
  bbox: [number, number, number, number]
  extendsBus?: string
}

export interface Details {
  province: string
  stretches: Record<string, Detail>
  /** Whole roads by key; absent in files published before them */
  roads?: Record<string, Road>
}

export interface Page {
  page: number
  entries: Entry[]
}

/** A bus route's most-wanted extension: the stretch with the most points from extensions of it (W05) */
export interface Wanted {
  slug: string
  name: string
  /** Province slug */
  province: string
  points: number
  rankProvince: number | null
}

/** An existing bus route (W05); lines are [lon, lat] pairs */
export interface BusRoute {
  number: string
  name: string
  startName: string
  endName: string
  start: [number, number]
  end: [number, number]
  /** Towns it passes, in order */
  towns: string[]
  lengthOutM: number
  lengthBackM: number
  out: [number, number][]
  back: [number, number][]
  bbox: [number, number, number, number]
  wanted: Wanted | null
}

export interface Buses {
  routes: BusRoute[]
}

/** Link slug to [the stretch's own slug, province slug] */
export type SlugIndex = Record<string, [string, string]>

export type Heat = FeatureCollection<MultiLineString, { s: string; p: number }>

const files = new Map<string, Promise<unknown>>()

/** A hashed file: fetched once per visit, since its content never changes */
export function loadFile<T>(key: string): Promise<T> {
  let file = files.get(key)
  if (!file) {
    file = fetch(`${dataUrl()}/${key}`).then((r) => {
      if (!r.ok) throw new Error(`${key}: ${r.status}`)
      return r.json()
    })
    file.catch(() => files.delete(key))
    files.set(key, file)
  }
  return file as Promise<T>
}

export type ManifestState =
  | { kind: 'loading' }
  | { kind: 'loaded'; manifest: Manifest }
  /** Nothing published yet, or publishing is off */
  | { kind: 'none' }
  | { kind: 'error' }

let manifestState: ManifestState = dataUrl() ? { kind: 'loading' } : { kind: 'none' }
let manifestAt = 0
let manifestRequest: Promise<void> | null = null
const listeners = new Set<() => void>()

function setManifest(state: ManifestState) {
  manifestState = state
  listeners.forEach((l) => l())
}

/** Loads the manifest, again only after a minute (it is cached for a minute on R2 too) */
export function loadManifest(): Promise<void> {
  if (!dataUrl()) return Promise.resolve()
  if (manifestRequest) return manifestRequest
  if (manifestState.kind === 'loaded' && Date.now() - manifestAt < 60_000) return Promise.resolve()
  manifestRequest = fetch(`${dataUrl()}/manifest.json`)
    .then(async (r) => {
      if (r.status === 404 || r.status === 403) return setManifest({ kind: 'none' })
      if (!r.ok) throw new Error(String(r.status))
      manifestAt = Date.now()
      setManifest({ kind: 'loaded', manifest: (await r.json()) as Manifest })
    })
    .catch(() => {
      if (manifestState.kind !== 'loaded') setManifest({ kind: 'error' })
    })
    .finally(() => {
      manifestRequest = null
    })
  return manifestRequest
}

export function useManifest(): ManifestState {
  const state = useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => manifestState,
  )
  useEffect(() => {
    loadManifest()
  }, [])
  return state
}

/** Whether nobody has voted in the province yet (W08); false until the manifest says so */
export function useNoVotes(province: string): boolean {
  const manifest = useManifest()
  const entry = manifest.kind === 'loaded' ? manifest.manifest.provinces[province] : undefined
  return entry !== undefined && entry.stretches === 0
}

/** For tests */
export function resetRankings() {
  files.clear()
  manifestState = dataUrl() ? { kind: 'loading' } : { kind: 'none' }
  manifestAt = 0
  manifestRequest = null
}

/** A hashed file as React state: undefined while loading or when key is null, null on error */
export function useFile<T>(key: string | null | undefined): T | null | undefined {
  const [loaded, setLoaded] = useState<{ key: string; value: T | null }>()
  useEffect(() => {
    if (!key) return
    let live = true
    loadFile<T>(key).then(
      (value) => live && setLoaded({ key, value }),
      () => live && setLoaded({ key, value: null }),
    )
    return () => {
      live = false
    }
  }, [key])
  return key && loaded?.key === key ? loaded.value : undefined
}

/** Which of the five colours a score gets */
export function band(points: number, bands: number[]): number {
  const index = bands.filter((b) => points >= b).length
  // With fewer than four thresholds, spread the colours that are used over the scale
  return bands.length === 0 ? 2 : Math.round((index * (HEAT.length - 1)) / bands.length)
}

/** "9:30", the time of the last update in the visitor's time zone */
export function updatedAt(manifest: Manifest): string {
  return new Date(manifest.generatedAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
}

export const formatNumber = (n: number) => n.toLocaleString('en-US')
