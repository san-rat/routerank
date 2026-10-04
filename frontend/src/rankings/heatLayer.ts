import type { ExpressionSpecification, GeoJSONSource, Map as MapLibreMap, MapLayerMouseEvent, Popup } from 'maplibre-gl'
import { HEAT, type Heat } from './data'

const SOURCE = 'heat'
const LINES = 'heat-lines'
const SELECTED = 'heat-selected'
/** Invisible and wide: thin lines are hard to tap on a phone */
const HIT = 'heat-hit'
const EMPTY: Heat = { type: 'FeatureCollection', features: [] }

/** Runs once the style can take new sources */
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

/** Five colours stepped by the national bands, so a colour means the same in every province */
function colour(bands: number[]): ExpressionSpecification | string {
  if (bands.length === 0) return HEAT[2]
  const stops: (number | string)[] = []
  bands.forEach((b, i) => stops.push(b, HEAT[Math.round(((i + 1) * (HEAT.length - 1)) / bands.length)]))
  return ['step', ['get', 'p'], HEAT[0], ...stops] as ExpressionSpecification
}

export interface HeatHandlers {
  onSelect?: (slug: string) => void
  /** Desktop hover tooltip text for a stretch */
  tooltip?: (slug: string) => string | undefined
}

/**
 * The vote heatmap: one province's stretches drawn on the road, pale mint to dark teal by points, under the
 * map labels. With a stretch selected, the others fade and it is drawn thicker (W04).
 */
export function createHeatLayer(map: MapLibreMap, handlers: HeatHandlers) {
  let removed = false
  let popup: Popup | null = null
  let latest: { heat: Heat | null; bands: number[]; selected: string | null } | null = null

  const onClick = (e: MapLayerMouseEvent) => {
    const slug = e.features?.[0]?.properties?.s
    if (typeof slug === 'string') {
      e.preventDefault()
      handlers.onSelect?.(slug)
    }
  }
  const onMove = (e: MapLayerMouseEvent) => {
    map.getCanvas().style.cursor = 'pointer'
    const slug = e.features?.[0]?.properties?.s
    const text = typeof slug === 'string' ? handlers.tooltip?.(slug) : undefined
    if (!text || !window.matchMedia('(hover: hover)').matches) return
    import('maplibre-gl').then(({ Popup }) => {
      if (removed) return
      popup ??= new Popup({ closeButton: false, closeOnClick: false, className: 'heat-tooltip', offset: 10 })
      popup.setLngLat(e.lngLat).setText(text).addTo(map)
    })
  }
  const onLeave = () => {
    map.getCanvas().style.cursor = ''
    popup?.remove()
  }

  whenStyleReady(map, () => {
    if (removed || map.getSource(SOURCE)) return
    map.addSource(SOURCE, { type: 'geojson', data: EMPTY })
    // Under the first label layer: labels always stay on top (Figma "Map / Labels")
    const beforeId = map.getStyle().layers.find((l) => l.type === 'symbol')?.id
    map.addLayer(
      {
        id: LINES,
        type: 'line',
        source: SOURCE,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': HEAT[2],
          'line-width': ['interpolate', ['linear'], ['zoom'], 7, 2, 11, 4, 15, 8],
        },
      },
      beforeId,
    )
    map.addLayer(
      {
        id: SELECTED,
        type: 'line',
        source: SOURCE,
        filter: ['==', ['get', 's'], ''],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': HEAT[4],
          'line-width': ['interpolate', ['linear'], ['zoom'], 7, 4, 11, 7, 15, 12],
        },
      },
      beforeId,
    )
    map.addLayer(
      {
        id: HIT,
        type: 'line',
        source: SOURCE,
        paint: { 'line-color': '#000', 'line-opacity': 0, 'line-width': 18 },
      },
      beforeId,
    )
    map.on('click', HIT, onClick)
    map.on('mousemove', HIT, onMove)
    map.on('mouseleave', HIT, onLeave)
  })

  return {
    update(nextHeat: Heat | null, nextBands: number[], nextSelected: string | null) {
      // A run that waited for the style uses the newest state, never an older one
      latest = { heat: nextHeat, bands: nextBands, selected: nextSelected }
      whenStyleReady(map, () => {
        if (removed || !latest) return
        const { heat, bands, selected } = latest
        ;(map.getSource(SOURCE) as GeoJSONSource | undefined)?.setData(heat ?? EMPTY)
        if (!map.getLayer(LINES)) return
        map.setPaintProperty(LINES, 'line-color', colour(bands))
        map.setPaintProperty(LINES, 'line-opacity', selected ? 0.35 : 1)
        map.setFilter(SELECTED, ['==', ['get', 's'], selected ?? ''])
      })
    },
    remove() {
      removed = true
      popup?.remove()
      map.off('click', HIT, onClick)
      map.off('mousemove', HIT, onMove)
      map.off('mouseleave', HIT, onLeave)
      if (!map.getStyle()) return
      if (map.getLayer(HIT)) map.removeLayer(HIT)
      if (map.getLayer(SELECTED)) map.removeLayer(SELECTED)
      if (map.getLayer(LINES)) map.removeLayer(LINES)
      if (map.getSource(SOURCE)) map.removeSource(SOURCE)
    },
  }
}

export type HeatLayer = ReturnType<typeof createHeatLayer>
