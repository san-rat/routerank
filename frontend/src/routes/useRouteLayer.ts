import type { Map as MapLibreMap } from 'maplibre-gl'
import { useEffect, useRef, useState } from 'react'
import type { RouteDrawing, RouteEditing, RouteLayer } from '../map/routeLayer'

type Padding = { top: number; bottom: number; left: number; right: number }

/**
 * Draws a route on the shared map while the component is mounted. The drawing code comes with MapLibre's chunk,
 * which is already loaded whenever a map is on screen. {@code fitKey} changes when the map should move to show
 * the route (a new route, not every drag).
 */
export function useRouteLayer(
  map: MapLibreMap | null,
  drawing: RouteDrawing,
  editing: RouteEditing,
  fit?: { key: string; padding: Padding },
) {
  const [layer, setLayer] = useState<RouteLayer | null>(null)
  const handlers = useRef(editing)
  handlers.current = editing
  const editable = Boolean(editing.onMovePoint)

  useEffect(() => {
    if (!map) return
    let current: RouteLayer | null = null
    let cancelled = false
    import('../map/routeLayer').then(({ createRouteLayer }) => {
      if (cancelled) return
      // Handlers go through the ref, so the layer always calls the latest ones
      current = createRouteLayer(map, {
        onLineClick: editable ? (p) => handlers.current.onLineClick?.(p) : undefined,
        onMovePoint: editable ? (w, p) => handlers.current.onMovePoint?.(w, p) : undefined,
        onMoveWaypoint: editable ? (i, p) => handlers.current.onMoveWaypoint?.(i, p) : undefined,
        onRemoveWaypoint: editable ? (i) => handlers.current.onRemoveWaypoint?.(i) : undefined,
      })
      setLayer(current)
    })
    return () => {
      cancelled = true
      current?.remove()
      setLayer(null)
    }
  }, [map, editable])

  useEffect(() => {
    layer?.update(drawing)
  }, [layer, drawing])

  const fitKey = fit?.key
  useEffect(() => {
    if (layer && fit && fitKey) layer.fit(drawing, fit.padding)
    // Only when the key changes (a different route), not on every redraw
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [layer, fitKey])
}
