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
        Draw each route with the same main-road routing as voters' routes. The way back is the fastest one unless you
        give it waypoints of its own. They show on the map after the next scoring run.
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
  'NO_ROAD_NEARBY:backWaypoint': 'A way-back waypoint has no main road near it.',
  NO_ROUTE: "These points can't be joined on main roads.",
  NO_WAY_BACK: 'There is no way back on main roads.',
  NUMBER_TAKEN: 'Another active bus route has this number.',
}

const MAX_WAYPOINTS = 25
const MOBILE_FIT = { top: 60, bottom: 420, left: 40, right: 40 }
const DESKTOP_FIT = { top: 60, bottom: 60, left: 60, right: 60 }

/**
 * /admin/buses/new and /admin/buses/:id — draw a bus route: tap the map for the start, then the end; drag the pins,
 * tap the line to add a waypoint, tap a waypoint to remove it. The server routes it both ways. Switched to
 * "Way back", the same moves shape the way back with waypoints of its own (for a bus that comes back on another
 * road); without any, the way back is the fastest.
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
  const [backWaypoints, setBackWaypoints] = useState<LatLon[]>([])
  const [leg, setLeg] = useState<'out' | 'back'>('out')
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
      setBackWaypoints(b.backWaypoints)
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
        .drawBusRoute(
          { id: editingId ?? undefined, number: number.trim() || undefined, start, end, waypoints, backWaypoints },
          controller.signal,
        )
        .then(setDrawing, (e: unknown) => !controller.signal.aborted && setError(describe(e)))
    }, 300)
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
    // describe only reads the context
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [start, end, waypoints, backWaypoints, number, editingId])

  // The direction being shaped is drawn solid with its waypoints, the other dashed. On the way back the bus starts
  // at the end, so the pins swap.
  const shown = useMemo(() => {
    const first = drawing?.points[0] ?? start
    const last = drawing?.points.at(-1) ?? end
    const there = drawing?.out ?? []
    const back = drawing?.back ?? []
    return leg === 'out'
      ? { out: there, backLeaves: back.length > 1 ? [back] : [], start: first, end: last, waypoints }
      : { out: back, backLeaves: there.length > 1 ? [there] : [], start: last, end: first, waypoints: backWaypoints }
  }, [drawing, start, end, waypoints, backWaypoints, leg])
  const setShaped = leg === 'out' ? setWaypoints : setBackWaypoints
  useRouteLayer(
    map,
    shown,
    {
      onLineClick: (p) => {
        if (shown.waypoints.length >= MAX_WAYPOINTS) return
        const index = waypointIndex(shown.out, shown.waypoints, p)
        setShaped((w) => [...w.slice(0, index), p, ...w.slice(index)])
      },
      onMovePoint: (which, p) => ((which === 'start') === (leg === 'out') ? setStart(p) : setEnd(p)),
      onMoveWaypoint: (i, p) => setShaped((w) => w.map((x, j) => (j === i ? p : x))),
      onRemoveWaypoint: (i) => setShaped((w) => w.filter((_, j) => j !== i)),
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
      backWaypoints,
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
        {!start
          ? 'Tap the map at the start (a main road).'
          : !end
            ? 'Now tap the end.'
            : leg === 'out'
              ? 'Drag the pins or the line to follow the bus. Tap a waypoint to remove it.'
              : 'Drag the way back (solid) onto the roads the bus comes back on; the way there is dashed. Tap a waypoint to remove it.'}
      </p>
      {start && end && (
        <div className="admin-actions" role="group" aria-label="Direction to shape">
          <button type="button" className={`admin-button${leg === 'out' ? '' : ' plain'}`} aria-pressed={leg === 'out'} onClick={() => setLeg('out')}>
            Way there
          </button>
          <button type="button" className={`admin-button${leg === 'back' ? '' : ' plain'}`} aria-pressed={leg === 'back'} onClick={() => setLeg('back')}>
            Way back
          </button>
          {backWaypoints.length > 0 && (
            <button type="button" className="admin-button plain" onClick={() => setBackWaypoints([])}>
              Reset way back
            </button>
          )}
        </div>
      )}
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
          {startName || drawing.startName} → {endName || drawing.endName} · there {formatKm(drawing.lengthOutM)} (
          {waypoints.length} of {MAX_WAYPOINTS} waypoints), back {formatKm(drawing.lengthBackM)} (
          {backWaypoints.length > 0 ? `${backWaypoints.length} of ${MAX_WAYPOINTS} waypoints` : 'the fastest'}
          {drawing.backVia.length > 0 && `, uses ${drawing.backVia.join(' and ')}`})
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
        <button type="button" className="admin-button plain" onClick={() => { setStart(undefined); setEnd(undefined); setWaypoints([]); setBackWaypoints([]); setLeg('out') }}>
          Start again
        </button>
        <Link className="admin-button plain" to="/admin/buses">
          Cancel
        </Link>
      </div>
    </section>
  )
}
