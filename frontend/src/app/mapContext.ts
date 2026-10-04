import type { Map as MapLibreMap } from 'maplibre-gl'
import { createContext, useContext } from 'react'

/** The one map AppShell keeps on screen, for pages that draw on it (the add-route steps) */
export const MapContext = createContext<MapLibreMap | null>(null)

export function useMap(): MapLibreMap | null {
  return useContext(MapContext)
}
