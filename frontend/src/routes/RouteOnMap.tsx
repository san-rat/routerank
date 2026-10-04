import { useEffect, useMemo } from 'react'
import { useSearchParams } from 'react-router'
import { useMap } from '../app/mapContext'
import { DESKTOP, useMediaQuery } from '../app/useMediaQuery'
import { loadAccount, useAccount } from '../auth/account'
import { loadMyRoutes, useMyRoutes } from './myRoutes'
import { useRouteLayer } from './useRouteLayer'

const MOBILE_FIT = { top: 90, bottom: 230, left: 40, right: 40 }
const DESKTOP_FIT = { top: 60, bottom: 60, left: 60, right: 60 }

/** Map pages with ?route=ID: one of the signed-in user's routes drawn on the map ("Show on map", "See it on the map") */
export function RouteOnMap() {
  const [params] = useSearchParams()
  const id = Number(params.get('route'))
  return id ? <DrawRoute id={id} /> : null
}

function DrawRoute({ id }: { id: number }) {
  const account = useAccount()
  const routes = useMyRoutes()
  const map = useMap()
  const desktop = useMediaQuery(DESKTOP)

  useEffect(() => {
    loadAccount()
  }, [])
  useEffect(() => {
    if (account.kind === 'signed-in') loadMyRoutes()
  }, [account.kind])

  const route = routes.kind === 'loaded' ? routes.data.routes.find((r) => r.id === id) : undefined
  const drawing = useMemo(
    () => ({ out: route?.out ?? [], backLeaves: [], start: route?.start, end: route?.end, waypoints: [] }),
    [route],
  )
  useRouteLayer(map, drawing, {}, route ? { key: `route-${route.id}`, padding: desktop ? DESKTOP_FIT : MOBILE_FIT } : undefined)
  return null
}
