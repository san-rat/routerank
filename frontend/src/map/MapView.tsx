import 'maplibre-gl/dist/maplibre-gl.css'
import { addProtocol, Map as MapLibreMap, setWorkerUrl } from 'maplibre-gl'
// MapLibre builds its worker URL at runtime, which bundlers can't follow; bundle it explicitly
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'
import { Protocol } from 'pmtiles'
import { useEffect, useRef } from 'react'
import { SRI_LANKA_BOUNDS, type Province } from './provinces'
import { mapStyle } from './style'

const TILES_URL = import.meta.env.VITE_TILES_URL

let protocolAdded = false

interface Props {
  province?: Province
  /** Space covered by panels, so fitted provinces stay visible */
  padding: { top: number; bottom: number; left: number; right: number }
  onReady?: (map: MapLibreMap) => void
}

export default function MapView({ province, padding, onReady }: Props) {
  const container = useRef<HTMLDivElement>(null)
  const map = useRef<MapLibreMap | null>(null)

  useEffect(() => {
    if (!protocolAdded) {
      setWorkerUrl(workerUrl)
      addProtocol('pmtiles', new Protocol().tile)
      protocolAdded = true
    }
    const m = new MapLibreMap({
      container: container.current!,
      style: mapStyle(TILES_URL),
      bounds: province?.bbox,
      fitBoundsOptions: { padding },
      maxBounds: SRI_LANKA_BOUNDS,
      minZoom: 6,
      maxZoom: 17,
      // Cheap phones: no rotation or tilt
      dragRotate: false,
      pitchWithRotate: false,
      touchPitch: false,
      maxPitch: 0,
      attributionControl: { compact: true },
    })
    m.touchZoomRotate.disableRotation()
    m.keyboard.disableRotation()
    map.current = m
    onReady?.(m)
    // Lets browser checks inspect rendered labels in development
    if (import.meta.env.DEV) (window as unknown as { __map: MapLibreMap }).__map = m
    return () => {
      m.remove()
      map.current = null
    }
    // The map is created once; province changes are handled by the effect below
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (province && map.current) map.current.fitBounds(province.bbox, { padding, duration: 600 })
    // Refit only when the province changes, not when the padding object is recreated
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [province?.slug])

  return <div ref={container} className="map" data-testid="map" />
}
