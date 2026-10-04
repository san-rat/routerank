import type { Map as MapLibreMap } from 'maplibre-gl'
import { lazy, Suspense, useRef, useState } from 'react'
import { Link, Outlet, useLocation, useMatch } from 'react-router'
import locateIcon from '../assets/icons/locate.svg'
import plus from '../assets/icons/plus.svg'
import { BusChip, BusesOnMap } from '../buses/Buses'
import { ProvincePicker } from '../map/ProvincePicker'
import { DEFAULT_PROVINCE, provinceBySlug } from '../map/provinces'
import { useFocus } from '../rankings/focus'
import { HeatOnMap } from '../rankings/HeatOnMap'
import { useNoVotes } from '../rankings/data'
import { Legend, NoVotes } from '../rankings/MapRankings'
import { draftActions } from '../routes/draft'
import { RouteOnMap } from '../routes/RouteOnMap'
import { MapContext } from './mapContext'
import { PanelHeader, WebNav } from './Navigation'
import { useSection } from './section'
import { DESKTOP, useMediaQuery } from './useMediaQuery'

// MapLibre is loaded only when a map is on screen
const MapView = lazy(() => import('../map/MapView'))

// Space taken by the floating chips and nav on mobile, so a fitted province stays visible
const MOBILE_PADDING = { top: 70, bottom: 90, left: 24, right: 24 }
const DESKTOP_PADDING = { top: 70, bottom: 40, left: 40, right: 40 }
// A stretch on show: on phones its sheet covers the lower half (W04)
const MOBILE_FOCUS_PADDING = { top: 120, bottom: 440, left: 40, right: 40 }
const DESKTOP_FOCUS_PADDING = { top: 80, bottom: 60, left: 60, right: 60 }

export function AppShell() {
  const desktop = useMediaQuery(DESKTOP)
  const { pathname } = useLocation()
  const onMapPage = useSection() === 'map'
  // The first two add-route steps draw on the map, so it shows there on phones too
  // So does drawing a bus route on the admin pages
  const drawing = pathname === '/add' || pathname === '/add/route' || /^\/admin\/buses\/[^/]+$/.test(pathname)
  const match = useMatch('/map/:province')
  const stretchPage = useMatch('/s/:stretch') !== null
  // A stretch's or a bus route's page: the map zooms to it and its sheet covers the lower half on phones
  const busPage = useMatch('/bus/:number') !== null
  const focusPage = stretchPage || busPage
  const focus = useFocus()
  // On a stretch's page the map shows its province (from the overall leaderboard, that can be another province)
  const slug = match?.params.province ?? (focusPage && focus ? focus.province : DEFAULT_PROVINCE)
  const province = provinceBySlug(slug) ?? provinceBySlug(DEFAULT_PROVINCE)!
  // W08 shows only its own card: no legend, and its button adds the first route
  const noVotes = useNoVotes(province.slug) && !focusPage
  const [map, setMap] = useState<MapLibreMap | null>(null)
  const [locating, setLocating] = useState(false)
  const toastTimer = useRef<ReturnType<typeof setTimeout>>(undefined)

  // Locate me (W09): the explainer shows while the browser asks for permission; the location only centres the map
  function locate() {
    setLocating(true)
    clearTimeout(toastTimer.current)
    const done = () => {
      toastTimer.current = setTimeout(() => setLocating(false), 1500)
    }
    if (!navigator.geolocation) return done()
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        map?.flyTo({ center: [pos.coords.longitude, pos.coords.latitude], zoom: 14 })
        done()
      },
      done,
      { enableHighAccuracy: false, timeout: 10000 },
    )
  }

  const mapLayer = (desktop || onMapPage || drawing) && (
    <div className="map-area">
      <Suspense fallback={<div className="map-loading" />}>
        <MapView province={province} padding={desktop ? DESKTOP_PADDING : MOBILE_PADDING} onReady={setMap}
          focus={focusPage ? focus?.bbox : undefined}
          focusPadding={desktop ? DESKTOP_FOCUS_PADDING : MOBILE_FOCUS_PADDING} />
      </Suspense>
      {!drawing && (
        <div className="map-chips">
          <ProvincePicker current={province} />
          <BusChip />
        </div>
      )}
      <button className={`icon-button locate ${drawing ? 'drawing' : ''}`} onClick={locate} aria-label="Show my location">
        <img src={locateIcon} alt="" width={20} height={20} />
      </button>
      {locating && (
        <p className="toast locate-toast" role="status">
          We only use your location to centre the map. It's never saved.
        </p>
      )}
      {onMapPage && !drawing && <HeatOnMap province={province.slug} />}
      {/* Bus routes show behind the route preview too, for the Bus check (W18) */}
      {((onMapPage && !drawing) || pathname === '/add/route') && <BusesOnMap />}
      {onMapPage && (desktop || !focusPage) && !noVotes && <Legend />}
      {onMapPage && !focusPage && <NoVotes province={province.slug} />}
      {onMapPage && (desktop || !focusPage) && !noVotes && (
        <Link className="add-route-button" to="/add" onClick={() => draftActions.reset()}>
          <img src={plus} alt="" width={20} height={20} /> Add route
        </Link>
      )}
      {onMapPage && <RouteOnMap />}
    </div>
  )

  if (desktop) {
    return (
      <MapContext value={map}>
        <div className="app">
          <aside className="panel">
            <PanelHeader />
            <Outlet />
          </aside>
          {mapLayer}
        </div>
      </MapContext>
    )
  }
  return (
    <MapContext value={map}>
      <div className="app">
        {mapLayer}
        <Outlet />
        {!pathname.startsWith('/add') && !focusPage && <WebNav />}
      </div>
    </MapContext>
  )
}
