import type { Map as MapLibreMap } from 'maplibre-gl'
import { lazy, Suspense, useState } from 'react'
import { Outlet, useMatch } from 'react-router'
import locateIcon from '../assets/icons/locate.svg'
import { ProvincePicker } from '../map/ProvincePicker'
import { DEFAULT_PROVINCE, provinceBySlug } from '../map/provinces'
import { PanelHeader, WebNav } from './Navigation'
import { useSection } from './section'
import { DESKTOP, useMediaQuery } from './useMediaQuery'

// MapLibre is loaded only when a map is on screen
const MapView = lazy(() => import('../map/MapView'))

// Space taken by the floating chips and nav on mobile, so a fitted province stays visible
const MOBILE_PADDING = { top: 70, bottom: 90, left: 24, right: 24 }
const DESKTOP_PADDING = { top: 70, bottom: 40, left: 40, right: 40 }

export function AppShell() {
  const desktop = useMediaQuery(DESKTOP)
  const onMapPage = useSection() === 'map'
  const match = useMatch('/map/:province')
  const province = provinceBySlug(match?.params.province ?? DEFAULT_PROVINCE) ?? provinceBySlug(DEFAULT_PROVINCE)!
  const [map, setMap] = useState<MapLibreMap | null>(null)

  // Locate me: the browser's own permission prompt; the explainer screen comes in Phase 4
  function locate() {
    navigator.geolocation?.getCurrentPosition(
      (pos) => map?.flyTo({ center: [pos.coords.longitude, pos.coords.latitude], zoom: 14 }),
      () => {},
      { enableHighAccuracy: false, timeout: 10000 },
    )
  }

  const mapLayer = (desktop || onMapPage) && (
    <div className="map-area">
      <Suspense fallback={<div className="map-loading" />}>
        <MapView province={province} padding={desktop ? DESKTOP_PADDING : MOBILE_PADDING} onReady={setMap} />
      </Suspense>
      <div className="map-chips">
        <ProvincePicker current={province} />
      </div>
      <button className="icon-button locate" onClick={locate} aria-label="Show my location">
        <img src={locateIcon} alt="" width={20} height={20} />
      </button>
    </div>
  )

  if (desktop) {
    return (
      <div className="app">
        <aside className="panel">
          <PanelHeader />
          <Outlet />
        </aside>
        {mapLayer}
      </div>
    )
  }
  return (
    <div className="app">
      {mapLayer}
      <Outlet />
      <WebNav />
    </div>
  )
}
