import { type GeoJSONSource, LngLatBounds, type Map as MapLibreMap, type MapLayerMouseEvent, Marker } from 'maplibre-gl'
import type { LatLon } from '../api/client'
import pinEnd from '../assets/icons/pin-end.svg'
import pinStart from '../assets/icons/pin-start.svg'
import type { Position } from '../routes/geometry'

// The user's route is teal (Figma); the start pin is blue, the end pin teal
const TEAL = '#0f766e'
const SOURCE = 'route-draft'

export interface RouteDrawing {
  out: Position[]
  /** Stretches where the way back leaves the way there, drawn dashed */
  backLeaves: Position[][]
  start?: LatLon
  end?: LatLon
  waypoints: LatLon[]
}

export interface RouteEditing {
  onLineClick?: (point: LatLon) => void
  onMovePoint?: (which: 'start' | 'end', point: LatLon) => void
  onMoveWaypoint?: (index: number, point: LatLon) => void
  onRemoveWaypoint?: (index: number) => void
}

/** Runs once the style can take new sources (the map's 'load' may have fired long ago, or tiles may still be loading) */
function whenStyleReady(map: MapLibreMap, run: () => void) {
  if (map.isStyleLoaded()) return run()
  let done = false
  const retry = () => {
    if (done || !map.isStyleLoaded()) return
    done = true
    map.off('styledata', retry)
    map.off('idle', retry)
    run()
  }
  map.on('styledata', retry)
  map.on('idle', retry)
}

function pin(src: string, label: string): HTMLElement {
  const el = document.createElement('div')
  el.className = 'route-pin'
  el.setAttribute('aria-label', label)
  const img = document.createElement('img')
  img.src = src
  img.alt = ''
  img.width = 28
  img.height = 36
  img.draggable = false
  el.appendChild(img)
  return el
}

function waypointDot(index: number): HTMLElement {
  const el = document.createElement('button')
  el.type = 'button'
  el.className = 'route-waypoint'
  el.setAttribute('aria-label', `Waypoint ${index + 1} (tap to remove)`)
  return el
}

const toLatLon = (lngLat: { lng: number; lat: number }): LatLon => ({ lat: lngLat.lat, lon: lngLat.lng })

/**
 * Draws a route on the map: the way there as a solid teal line, where the way back differs as a dashed one, and
 * the start, end and waypoint pins. With editing handlers, pins can be dragged, a tap on the line adds a waypoint
 * and a tap on a waypoint removes it. Callbacks fire when a drag ends, never during it.
 */
export function createRouteLayer(map: MapLibreMap, editing: RouteEditing = {}) {
  let markers: Marker[] = []
  let removed = false

  const onLineClick = (e: MapLayerMouseEvent) => {
    e.preventDefault()
    editing.onLineClick?.(toLatLon(e.lngLat))
  }
  const pointer = () => (map.getCanvas().style.cursor = editing.onLineClick ? 'copy' : '')
  const noPointer = () => (map.getCanvas().style.cursor = '')

  whenStyleReady(map, () => {
    if (removed) return
    map.addSource(SOURCE, { type: 'geojson', data: { type: 'FeatureCollection', features: [] } })
    map.addLayer({
      id: `${SOURCE}-casing`,
      type: 'line',
      source: SOURCE,
      filter: ['==', ['get', 'kind'], 'out'],
      layout: { 'line-join': 'round', 'line-cap': 'round' },
      paint: { 'line-color': '#ffffff', 'line-width': 9 },
    })
    map.addLayer({
      id: `${SOURCE}-out`,
      type: 'line',
      source: SOURCE,
      filter: ['==', ['get', 'kind'], 'out'],
      layout: { 'line-join': 'round', 'line-cap': 'round' },
      paint: { 'line-color': TEAL, 'line-width': 5 },
    })
    map.addLayer({
      id: `${SOURCE}-back`,
      type: 'line',
      source: SOURCE,
      filter: ['==', ['get', 'kind'], 'back'],
      layout: { 'line-join': 'round' },
      paint: { 'line-color': TEAL, 'line-width': 3, 'line-dasharray': [1.5, 1.5] },
    })
    // A wide invisible line, so the route is easy to tap on a phone
    map.addLayer({
      id: `${SOURCE}-hit`,
      type: 'line',
      source: SOURCE,
      filter: ['==', ['get', 'kind'], 'out'],
      paint: { 'line-color': '#000000', 'line-opacity': 0, 'line-width': 24 },
    })
    map.on('click', `${SOURCE}-hit`, onLineClick)
    map.on('mouseenter', `${SOURCE}-hit`, pointer)
    map.on('mouseleave', `${SOURCE}-hit`, noPointer)
  })

  // A run that waited for the style uses the newest drawing, so an older update can never land after a newer one
  let latest: RouteDrawing | null = null

  function update(next: RouteDrawing) {
    latest = next
    whenStyleReady(map, () => {
      if (removed || !latest) return
      const drawing = latest
      const features = [
        ...(drawing.out.length > 1
          ? [{ type: 'Feature' as const, properties: { kind: 'out' }, geometry: { type: 'LineString' as const, coordinates: drawing.out } }]
          : []),
        ...drawing.backLeaves.map((coordinates) => ({
          type: 'Feature' as const,
          properties: { kind: 'back' },
          geometry: { type: 'LineString' as const, coordinates },
        })),
      ]
      ;(map.getSource(SOURCE) as GeoJSONSource | undefined)?.setData({ type: 'FeatureCollection', features })
    })

    const drawing = next
    markers.forEach((m) => m.remove())
    markers = []
    const draggable = Boolean(editing.onMovePoint)
    for (const which of ['start', 'end'] as const) {
      const point = drawing[which]
      if (!point) continue
      const marker = new Marker({ element: pin(which === 'start' ? pinStart : pinEnd, which === 'start' ? 'Start' : 'End'), anchor: 'bottom', draggable })
        .setLngLat([point.lon, point.lat])
        .addTo(map)
      marker.on('dragend', () => editing.onMovePoint?.(which, toLatLon(marker.getLngLat())))
      markers.push(marker)
    }
    drawing.waypoints.forEach((point, index) => {
      const el = waypointDot(index)
      const marker = new Marker({ element: el, draggable: Boolean(editing.onMoveWaypoint) }).setLngLat([point.lon, point.lat]).addTo(map)
      let dragged = false
      marker.on('dragstart', () => (dragged = true))
      marker.on('dragend', () => editing.onMoveWaypoint?.(index, toLatLon(marker.getLngLat())))
      el.addEventListener('click', (e) => {
        e.stopPropagation()
        if (!dragged) editing.onRemoveWaypoint?.(index)
        dragged = false
      })
      markers.push(marker)
    })
  }

  /** Moves the map so the whole route shows inside the padding */
  function fit(drawing: RouteDrawing, padding: { top: number; bottom: number; left: number; right: number }) {
    const bounds = new LngLatBounds()
    drawing.out.forEach(([lon, lat]) => bounds.extend([lon, lat]))
    drawing.backLeaves.flat().forEach(([lon, lat]) => bounds.extend([lon, lat]))
    for (const p of [drawing.start, drawing.end]) if (p) bounds.extend([p.lon, p.lat])
    if (!bounds.isEmpty()) map.fitBounds(bounds, { padding, maxZoom: 15, duration: 500 })
  }

  function remove() {
    removed = true
    markers.forEach((m) => m.remove())
    markers = []
    if (!map.getStyle()) return
    map.off('click', `${SOURCE}-hit`, onLineClick)
    map.off('mouseenter', `${SOURCE}-hit`, pointer)
    map.off('mouseleave', `${SOURCE}-hit`, noPointer)
    for (const id of ['hit', 'back', 'out', 'casing']) if (map.getLayer(`${SOURCE}-${id}`)) map.removeLayer(`${SOURCE}-${id}`)
    if (map.getSource(SOURCE)) map.removeSource(SOURCE)
  }

  return { update, fit, remove }
}

export type RouteLayer = ReturnType<typeof createRouteLayer>
