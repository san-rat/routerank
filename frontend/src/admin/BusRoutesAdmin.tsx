import type { MapMouseEvent } from 'maplibre-gl'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { adminApi, type BusRouteView, type Drawing, type LatLon } from '../api/client'
import { useMap } from '../app/mapContext'
import { DESKTOP, useMediaQuery } from '../app/useMediaQuery'
import { formatKm, waypointIndex } from '../routes/geometry'
import { useRouteLayer } from '../routes/useRouteLayer'
import { ReasonAction } from './common'
import { useAdminError, when } from './session'

/** /admin/buses — the bus routes on the map, retired ones last */
export function BusRoutesPage() {
  const [routes, setRoutes] = useState<BusRouteView[]>()
  const [error, setError] = useState<string | null>(null)
  const describe = useAdminError()
  const reload = useCallback(() => {
    adminApi.busRoutes().then(setRoutes, (e: unknown) => setError(describe(e)))
    // describe only reads the context
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  useEffect(reload, [reload])
  return (
    <section className="admin-section">
      <h2>Bus routes</h2>
      <p className="muted">
        Draw each route with the same main-road routing as voters' routes; both directions come out automatically. They
        show on the map after the next scoring run.
      </p>
      <Link className="admin-button" to="/admin/buses/new">
        Add a bus route
      </Link>
      {error && <p role="alert">{error}</p>}
      <ul className="admin-list">
        {routes?.map((b) => (
          <li key={b.id} className={b.retiredAt ? 'removed' : undefined}>
            <span>
              <strong className="bus-number">{b.number}</strong> {b.startName} → {b.endName} ·{' '}
              {formatKm(Math.max(b.lengthOutM, b.lengthBackM))} · {b.extensions}{' '}
              {b.extensions === 1 ? 'extension' : 'extensions'}
              {b.retiredAt ? ` · retired ${when(b.retiredAt)}` : ''}
            </span>
            {!b.retiredAt && (
              <span className="admin-actions">
                {b.extensions === 0 && (
                  <Link className="admin-button plain" to={`/admin/buses/${b.id}`}>
                    Redraw
                  </Link>
                )}
                <ReasonAction label="Retire" danger run={(r) => adminApi.retireBusRoute(b.id, r)} onDone={reload} />
              </span>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}

const PROBLEMS: Record<string, string> = {
  'SIDE_ROAD:start': 'The start is off the main roads: move it onto one.',
  'SIDE_ROAD:end': 'The end is off the main roads: move it onto one.',
  'NO_ROAD_NEARBY:start': 'There is no main road near the start.',
  'NO_ROAD_NEARBY:end': 'There is no main road near the end.',
  'NO_ROAD_NEARBY:waypoint': 'A waypoint has no main road near it.',
  NO_ROUTE: "These points can't be joined on main roads.",
  NO_WAY_BACK: 'There is no way back on main roads.',
  NUMBER_TAKEN: 'Another active bus route has this number.',
}

const MAX_WAYPOINTS = 25
const MOBILE_FIT = { top: 60, bottom: 420, left: 40, right: 40 }
const DESKTOP_FIT = { top: 60, bottom: 60, left: 60, right: 60 }

/**
 * /admin/buses/new and /admin/buses/:id — draw a bus route: tap the map for the start, then the end; drag the pins,
 * tap the line to add a waypoint, tap a waypoint to remove it. The server routes it both ways.
 */
export function BusRouteFormPage() {
  const { id } = useParams()
  const editingId = id && id !== 'new' ? Number(id) : null
  const map = useMap()
  const desktop = useMediaQuery(DESKTOP)
  const navigate = useNavigate()
  const describe = useAdminError()
  const [number, setNumber] = useState('')
  const [startName, setStartName] = useState('')
  const [endName, setEndName] = useState('')
  const [start, setStart] = useState<LatLon>()
  const [end, setEnd] = useState<LatLon>()
  const [waypoints, setWaypoints] = useState<LatLon[]>([])
  const [drawing, setDrawing] = useState<Drawing>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const points = useRef({ start, end })
  points.current = { start, end }

  // Redrawing: start from the stored route
  useEffect(() => {
    if (!editingId) return
    adminApi.busRoutes().then((all) => {
      const b = all.find((r) => r.id === editingId)
      if (!b) return
      setNumber(b.number)
      setStartName(b.startName)
      setEndName(b.endName)
      setStart(b.start)
      setEnd(b.end)
      setWaypoints(b.waypoints)
    }, (e: unknown) => setError(describe(e)))
    // describe only reads the context
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editingId])

  // Tapping the map places the start, then the end
  useEffect(() => {
    if (!map) return
    const onClick = (e: MapMouseEvent) => {
      if (e.defaultPrevented) return
      const point = { lat: e.lngLat.lat, lon: e.lngLat.lng }
      if (!points.current.start) setStart(point)
      else if (!points.current.end) setEnd(point)
    }
    map.on('click', onClick)
    return () => {
      map.off('click', onClick)
    }
  }, [map])

  // Route it on the server when the points settle
  useEffect(() => {
    if (!start || !end) {
      setDrawing(undefined)
      return
    }
    const controller = new AbortController()
    const timer = setTimeout(() => {
      adminApi
        .drawBusRoute({ id: editingId ?? undefined, number: number.trim() || undefined, start, end, waypoints }, controller.signal)
        .then(setDrawing, (e: unknown) => !controller.signal.aborted && setError(describe(e)))
    }, 300)
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
    // describe only reads the context
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [start, end, waypoints, number, editingId])

  const shown = useMemo(
    () => ({
      out: drawing?.out ?? [],
      backLeaves: drawing && drawing.back.length > 1 ? [drawing.back] : [],
      start: drawing?.points[0] ?? start,
      end: drawing?.points.at(-1) ?? end,
      waypoints,
    }),
    [drawing, start, end, waypoints],
  )
  useRouteLayer(
    map,
    shown,
    {
      onLineClick: (p) => {
        if (waypoints.length >= MAX_WAYPOINTS) return
        const index = waypointIndex(drawing?.out ?? [], waypoints, p)
        setWaypoints((w) => [...w.slice(0, index), p, ...w.slice(index)])
      },
      onMovePoint: (which, p) => (which === 'start' ? setStart(p) : setEnd(p)),
      onMoveWaypoint: (i, p) => setWaypoints((w) => w.map((x, j) => (j === i ? p : x))),
      onRemoveWaypoint: (i) => setWaypoints((w) => w.filter((_, j) => j !== i)),
    },
    { key: `${start?.lat},${start?.lon}-${end?.lat},${end?.lon}-${drawing ? 1 : 0}`, padding: desktop ? DESKTOP_FIT : MOBILE_FIT },
  )

  const problems = drawing?.problems ?? []
  const ready = drawing && problems.length === 0 && number.trim() !== ''

  async function save(reason: string) {
    if (!start || !end) return
    setBusy(true)
    setError(null)
    const input = {
      id: editingId ?? undefined,
      number: number.trim(),
      startName: startName.trim() || undefined,
      endName: endName.trim() || undefined,
      start,
      end,
      waypoints,
      reason,
    }
    try {
      if (editingId) await adminApi.redrawBusRoute(editingId, input)
      else await adminApi.addBusRoute(input)
      navigate('/admin/buses')
    } catch (e) {
      setError(describe(e))
      throw e
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className={`admin-section bus-form${desktop ? '' : ' route-sheet'}`} aria-label={editingId ? 'Redraw a bus route' : 'Add a bus route'}>
      <h2>{editingId ? 'Redraw a bus route' : 'Add a bus route'}</h2>
      <p className="muted">
        {!start ? 'Tap the map at the start (a main road).' : !end ? 'Now tap the end.' : 'Drag the pins or the line to follow the bus. Tap a waypoint to remove it.'}
      </p>
      <label>
        Number
        <input value={number} onChange={(e) => setNumber(e.target.value)} placeholder="138 or 138/2" maxLength={12} required />
      </label>
      <label>
        Start name <span className="muted">(blank: the nearest place, {drawing?.startName ?? '…'})</span>
        <input value={startName} onChange={(e) => setStartName(e.target.value)} maxLength={60} />
      </label>
      <label>
        End name <span className="muted">(blank: {drawing?.endName ?? '…'})</span>
        <input value={endName} onChange={(e) => setEndName(e.target.value)} maxLength={60} />
      </label>
      {drawing && problems.length === 0 && (
        <p>
          {startName || drawing.startName} → {endName || drawing.endName} · there {formatKm(drawing.lengthOutM)}, back{' '}
          {formatKm(drawing.lengthBackM)} (dashed) · {waypoints.length} of {MAX_WAYPOINTS} waypoints
          {drawing.towns.length > 0 && <> · via {drawing.towns.join(' · ')}</>}
        </p>
      )}
      {problems.map((p) => (
        <p key={p} role="alert">
          {PROBLEMS[p] ?? p}
        </p>
      ))}
      {error && <p role="alert">{error}</p>}
      <div className="admin-actions">
        {ready && !busy && <ReasonAction label={editingId ? 'Save the new line' : 'Add this bus route'} run={save} />}
        <button type="button" className="admin-button plain" onClick={() => { setStart(undefined); setEnd(undefined); setWaypoints([]) }}>
          Start again
        </button>
        <Link className="admin-button plain" to="/admin/buses">
          Cancel
        </Link>
      </div>
    </section>
  )
}
