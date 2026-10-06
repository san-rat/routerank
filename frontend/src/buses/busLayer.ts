import type { FeatureCollection, MultiLineString } from 'geojson'
import type { ExpressionSpecification, GeoJSONSource, Map as MapLibreMap, MapLayerMouseEvent } from 'maplibre-gl'
import type { BusRoute } from '../rankings/data'

const SOURCE = 'buses'
const LINES = 'bus-lines'
const DASHED = 'bus-dashed'
const SELECTED = 'bus-selected'
const SELECTED_DASHED = 'bus-selected-dashed'
const ARROWS = 'bus-arrows'
const SELECTED_ARROWS = 'bus-selected-arrows'
/** Invisible and wide: thin lines are hard to tap on a phone */
const HIT = 'bus-hit'
const ARROW = 'bus-arrow'
const RED = '#e5352b'

/**
 * Each bus route is three features: its way there ({@code d: 'out'}), its way back ({@code 'back'}, for the arrows
 * only: where it runs on the same road the way there's line already shows it) and the parts of the way back that
 * leave the way there ({@code 'leave'}, dashed).
 */
type BusLines = FeatureCollection<MultiLineString, { n: string; d: 'out' | 'back' | 'leave' }>

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
    features: routes.flatMap((r) => [
      { type: 'Feature' as const, properties: { n: r.number, d: 'out' as const }, geometry: { type: 'MultiLineString' as const, coordinates: [r.out] } },
      { type: 'Feature' as const, properties: { n: r.number, d: 'back' as const }, geometry: { type: 'MultiLineString' as const, coordinates: [r.back] } },
      ...(r.backLeaves?.length
        ? [{ type: 'Feature' as const, properties: { n: r.number, d: 'leave' as const }, geometry: { type: 'MultiLineString' as const, coordinates: r.backLeaves } }]
        : []),
    ]),
  }
}

/**
 * A white arrowhead pointing along the line (+x), outlined in dark red, drawn pixel by pixel so it needs no canvas.
 * 2× for sharp screens: 16 CSS pixels.
 */
export function arrowImage(size = 32): { width: number; height: number; data: Uint8Array } {
  const data = new Uint8Array(size * size * 4)
  const s = size / 24
  const corners: [number, number][] = [[6 * s, 4 * s], [19 * s, 12 * s], [6 * s, 20 * s]]
  const outline = 2 * s
  // Signed distance from each edge, positive inside
  const edges = corners.map(([x1, y1], i) => {
    const [x2, y2] = corners[(i + 1) % 3]
    const length = Math.hypot(x2 - x1, y2 - y1)
    return (x: number, y: number) => ((x2 - x1) * (y - y1) - (y2 - y1) * (x - x1)) / length
  })
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const inside = Math.min(...edges.map((e) => e(x + 0.5, y + 0.5)))
      const i = (y * size + x) * 4
      if (inside >= outline) data.set([255, 255, 255, 255], i)
      else if (inside >= -outline / 2) data.set([0x9f, 0x1d, 0x16, 255], i) // #9f1d16, a darker RED
    }
  }
  return { width: size, height: size, data }
}

const isOut: ExpressionSpecification = ['==', ['get', 'd'], 'out']
const isLeave: ExpressionSpecification = ['==', ['get', 'd'], 'leave']
const isDirection: ExpressionSpecification = ['in', ['get', 'd'], ['literal', ['out', 'back']]]
const isBus = (number: string): ExpressionSpecification => ['==', ['get', 'n'], number]

/**
 * Existing bus routes in bright red, their own layer above the heatmap and under the labels: the way there solid,
 * the way back dashed where it takes another road, and arrows for the direction of travel (both ways along a road
 * the bus runs on in both directions). Tapping one opens it (/bus/:number); with one on show, the others fade and
 * its arrows show from further out.
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
    if (!map.hasImage(ARROW)) map.addImage(ARROW, arrowImage(), { pixelRatio: 2 })
    const beforeId = map.getStyle().layers.find((l) => l.type === 'symbol')?.id
    const width = ['interpolate', ['linear'], ['zoom'], 7, 1.5, 11, 3, 15, 6] as ExpressionSpecification
    const selectedWidth = ['interpolate', ['linear'], ['zoom'], 7, 3, 11, 5, 15, 9] as ExpressionSpecification
    const round = { 'line-cap': 'round', 'line-join': 'round' } as const
    map.addLayer({ id: LINES, type: 'line', source: SOURCE, filter: isOut, layout: round, paint: { 'line-color': RED, 'line-width': width, 'line-opacity': 0.9 } }, beforeId)
    map.addLayer(
      { id: DASHED, type: 'line', source: SOURCE, filter: isLeave, paint: { 'line-color': RED, 'line-width': width, 'line-opacity': 0.9, 'line-dasharray': [2, 1.5] } },
      beforeId,
    )
    map.addLayer({ id: SELECTED, type: 'line', source: SOURCE, filter: ['all', isOut, isBus('')], layout: round, paint: { 'line-color': RED, 'line-width': selectedWidth } }, beforeId)
    map.addLayer(
      {
        id: SELECTED_DASHED,
        type: 'line',
        source: SOURCE,
        filter: ['all', isLeave, isBus('')],
        paint: { 'line-color': RED, 'line-width': selectedWidth, 'line-dasharray': [2, 1.5] },
      },
      beforeId,
    )
    const arrows = {
      'symbol-placement': 'line',
      'icon-image': ARROW,
      'icon-rotation-alignment': 'map',
      'icon-allow-overlap': true,
      'icon-ignore-placement': true,
    } as const
    map.addLayer({ id: ARROWS, type: 'symbol', source: SOURCE, minzoom: 13, filter: isDirection, layout: { ...arrows, 'symbol-spacing': 120 } }, beforeId)
    map.addLayer(
      { id: SELECTED_ARROWS, type: 'symbol', source: SOURCE, minzoom: 10, filter: ['all', isDirection, isBus('')], layout: { ...arrows, 'symbol-spacing': 80 } },
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
        for (const id of [LINES, DASHED, SELECTED, SELECTED_DASHED, ARROWS, SELECTED_ARROWS, HIT]) map.setLayoutProperty(id, 'visibility', visibility)
        for (const id of [LINES, DASHED]) map.setPaintProperty(id, 'line-opacity', latest.selected ? 0.3 : 0.9)
        const selected = isBus(latest.selected ?? '')
        map.setFilter(SELECTED, ['all', isOut, selected])
        map.setFilter(SELECTED_DASHED, ['all', isLeave, selected])
        // Its faded dashes would show through the gaps in the bold ones
        map.setFilter(DASHED, ['all', isLeave, ['!', selected]])
        map.setFilter(SELECTED_ARROWS, ['all', isDirection, selected])
        // With one on show, only its arrows
        map.setFilter(ARROWS, latest.selected ? ['all', isDirection, selected] : isDirection)
      })
    },
    remove() {
      removed = true
      map.off('click', HIT, onClick)
      map.off('mouseenter', HIT, onEnter)
      map.off('mouseleave', HIT, onLeave)
      if (!map.getStyle()) return
      for (const id of [HIT, SELECTED_ARROWS, ARROWS, SELECTED_DASHED, SELECTED, DASHED, LINES]) if (map.getLayer(id)) map.removeLayer(id)
      if (map.getSource(SOURCE)) map.removeSource(SOURCE)
    },
  }
}

export type BusLayer = ReturnType<typeof createBusLayer>
