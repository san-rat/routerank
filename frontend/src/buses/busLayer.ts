import type { FeatureCollection, MultiLineString } from 'geojson'
import type { GeoJSONSource, Map as MapLibreMap, MapLayerMouseEvent } from 'maplibre-gl'
import type { BusRoute } from '../rankings/data'

const SOURCE = 'buses'
const LINES = 'bus-lines'
const SELECTED = 'bus-selected'
/** Invisible and wide: thin lines are hard to tap on a phone */
const HIT = 'bus-hit'
const RED = '#e5352b'

type BusLines = FeatureCollection<MultiLineString, { n: string }>

const EMPTY: BusLines = { type: 'FeatureCollection', features: [] }

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

export function busLines(routes: BusRoute[]): BusLines {
  return {
    type: 'FeatureCollection',
    features: routes.map((r) => ({
      type: 'Feature',
      properties: { n: r.number },
      geometry: { type: 'MultiLineString', coordinates: [r.out, r.back] },
    })),
  }
}

/**
 * Existing bus routes in bright red, their own layer above the heatmap and under the labels. Tapping one opens it
 * (/bus/:number); with one on show, the others fade.
 */
export function createBusLayer(map: MapLibreMap, onSelect: (number: string) => void) {
  let removed = false
  let latest: { lines: BusLines; selected: string | null; visible: boolean } | null = null

  const onClick = (e: MapLayerMouseEvent) => {
    const number = e.features?.[0]?.properties?.n
    if (typeof number === 'string') {
      e.preventDefault()
      onSelect(number)
    }
  }
  const onEnter = () => {
    map.getCanvas().style.cursor = 'pointer'
  }
  const onLeave = () => {
    map.getCanvas().style.cursor = ''
  }

  whenStyleReady(map, () => {
    if (removed || map.getSource(SOURCE)) return
    map.addSource(SOURCE, { type: 'geojson', data: EMPTY })
    const beforeId = map.getStyle().layers.find((l) => l.type === 'symbol')?.id
    map.addLayer(
      {
        id: LINES,
        type: 'line',
        source: SOURCE,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': RED,
          'line-width': ['interpolate', ['linear'], ['zoom'], 7, 1.5, 11, 3, 15, 6],
          'line-opacity': 0.9,
        },
      },
      beforeId,
    )
    map.addLayer(
      {
        id: SELECTED,
        type: 'line',
        source: SOURCE,
        filter: ['==', ['get', 'n'], ''],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': RED, 'line-width': ['interpolate', ['linear'], ['zoom'], 7, 3, 11, 5, 15, 9] },
      },
      beforeId,
    )
    map.addLayer({ id: HIT, type: 'line', source: SOURCE, paint: { 'line-color': '#000', 'line-opacity': 0, 'line-width': 18 } }, beforeId)
    map.on('click', HIT, onClick)
    map.on('mouseenter', HIT, onEnter)
    map.on('mouseleave', HIT, onLeave)
  })

  return {
    update(lines: BusLines, selected: string | null, visible: boolean) {
      latest = { lines, selected, visible }
      whenStyleReady(map, () => {
        if (removed || !latest || !map.getLayer(LINES)) return
        ;(map.getSource(SOURCE) as GeoJSONSource | undefined)?.setData(latest.lines)
        const visibility = latest.visible || latest.selected ? 'visible' : 'none'
        for (const id of [LINES, SELECTED, HIT]) map.setLayoutProperty(id, 'visibility', visibility)
        map.setPaintProperty(LINES, 'line-opacity', latest.selected ? 0.3 : 0.9)
        map.setFilter(SELECTED, ['==', ['get', 'n'], latest.selected ?? ''])
      })
    },
    remove() {
      removed = true
      map.off('click', HIT, onClick)
      map.off('mouseenter', HIT, onEnter)
      map.off('mouseleave', HIT, onLeave)
      if (!map.getStyle()) return
      for (const id of [HIT, SELECTED, LINES]) if (map.getLayer(id)) map.removeLayer(id)
      if (map.getSource(SOURCE)) map.removeSource(SOURCE)
    },
  }
}

export type BusLayer = ReturnType<typeof createBusLayer>
