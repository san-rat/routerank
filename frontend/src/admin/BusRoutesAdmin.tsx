import type { MapMouseEvent } from 'maplibre-gl'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { adminApi, type BusRouteView, type Drawing, type LatLon } from '../api/client'
import { useMap } from '../app/mapContext'
import { DESKTOP, useMediaQuery } from '../app/useMediaQuery'
import { formatKm, waypointIndex } from '../routes/geometry'
import { useRouteLayer } from '../routes/useRouteLayer'
import { Empty, PageHeader, ReasonAction } from './common'
import { plural } from './format'
import { AdminIcon, Chip } from './icons'
import { useAdminError } from './session'

const day = (iso: string) => new Date(iso).toLocaleDateString('en-LK', { day: 'numeric', month: 'short' })

/** /admin/buses — A06: the bus routes on the map, retired ones last */
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
  const retired = routes?.filter((b) => b.retiredAt).length ?? 0
  return (
    <>
      <PageHeader
        title="Bus routes"
        actions={
          <Link className="a-btn primary" to="/admin/buses/new">
            <AdminIcon name="plus" /> Add a bus route
          </Link>
        }
      >
        Existing metro bus routes, drawn on main roads. They show on the map after the next scoring run. A route that
        others extend can’t be redrawn: retire it and add a new one.
      </PageHeader>
      {error && (
        <p role="alert" className="admin-alert">
          {error}
        </p>
      )}
      {routes?.length === 0 && <Empty title="No bus routes yet.">Add the first one to show it on the map.</Empty>}
      {routes && routes.length > 0 && (
        <section className="admin-card table-card">
          <p className="table-caption">
            {routes.length - retired} active · {retired} retired
          </p>
          <ul className="bus-list">
            {routes.map((b) => (
              <li key={b.id} className={b.retiredAt ? 'retired' : undefined}>
                <span className="bus-number">{b.number}</span>
                <span className="bus-text">
                  <strong>
                    {b.startName} → {b.endName}
                  </strong>
                  <span>{b.towns.length > 0 ? `Via ${b.towns.slice(0, 3).join(' · ')}` : 'No towns on the way'}</span>
                </span>
                <span className="bus-length">
                  {formatKm(b.lengthOutM)} there · {formatKm(b.lengthBackM)} back
                </span>
                <span className="bus-meta">
                  {b.retiredAt ? (
                    <Chip tone="red">Retired</Chip>
                  ) : (
                    <Chip>{b.extensions === 0 ? 'No extensions' : plural(b.extensions, 'extension')}</Chip>
                  )}
                  <span>{b.retiredAt ? `Retired ${day(b.retiredAt)}` : `Updated ${day(b.updatedAt)}`}</span>
                </span>
                {!b.retiredAt && (
                  <span className="admin-actions">
                    {b.extensions === 0 && (
                      <Link className="a-btn plain" to={`/admin/buses/${b.id}`}>
                        Redraw
                      </Link>
                    )}
                    <ReasonAction
                      label="Retire"
                      kind="danger"
                      title={`Retire bus ${b.number}?`}
                      description="It leaves the map at the next scoring run. Routes that extend it keep counting, but nothing new can extend it."
                      run={(r) => adminApi.retireBusRoute(b.id, r)}
                      onDone={reload}
                    />
                  </span>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
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
 * /admin/buses/new and /admin/buses/:id — A07: draw a bus route: tap the map for the start, then the end; drag the
 * pins, tap the line to add a waypoint, tap a waypoint to remove it. The server routes it both ways. Switched to
 * "Way back", the same moves shape the way back with waypoints of its own (for a bus that comes back on another
 * road); without any, the way back is the fastest. A panel beside the map on desktop, a sheet over it on phones.
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
  const [details, setDetails] = useState(false)
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

  const title = editingId ? `Redraw bus ${number.trim() || '…'}` : 'Add a bus route'
  // On phones the fields fold away under "Details" once there is a number, to keep the map in view
  const showFields = desktop || details || !number.trim()
  return (
    <section className={`bus-form${desktop ? '' : ' route-sheet'}`} aria-label={editingId ? 'Redraw a bus route' : 'Add a bus route'}>
      {!desktop && <div className="grabber" />}
      <Link className="back-link" to="/admin/buses">
        ‹ Bus routes
      </Link>
      <h1>{title}</h1>
      <p className="form-hint">
        {!start
          ? 'Tap the map at the start (a main road).'
          : !end
            ? 'Now tap the end.'
            : leg === 'out'
              ? 'Drag the pins or the line to follow the bus. Tap the line to add a waypoint, tap a waypoint to remove it.'
              : 'Drag the way back (solid) onto the roads the bus comes back on; the way there is dashed. Tap a waypoint to remove it.'}
      </p>
      {start && end && (
        <div className="segmented" role="group" aria-label="Direction to shape">
          <button type="button" aria-pressed={leg === 'out'} onClick={() => setLeg('out')}>
            <span aria-hidden="true">→</span> Way there
          </button>
          <button type="button" aria-pressed={leg === 'back'} onClick={() => setLeg('back')}>
            <span aria-hidden="true">←</span> Way back
          </button>
        </div>
      )}
      {showFields && (
        <div className="form-fields">
          <label>
            <span>
              Number <span className="hint">138 or 138/2</span>
            </span>
            <input value={number} onChange={(e) => setNumber(e.target.value)} maxLength={12} required />
          </label>
          <label>
            <span>
              Start name <span className="hint">blank: {drawing?.startName ?? 'the nearest place'}</span>
            </span>
            <input value={startName} onChange={(e) => setStartName(e.target.value)} maxLength={60} />
          </label>
          <label>
            <span>
              End name <span className="hint">blank: {drawing?.endName ?? 'the nearest place'}</span>
            </span>
            <input value={endName} onChange={(e) => setEndName(e.target.value)} maxLength={60} />
          </label>
        </div>
      )}
      {drawing && problems.length === 0 && (
        <div className="bus-summary">
          <div>
            <span className="dir" aria-hidden="true">
              →
            </span>
            <span>
              <strong>Way there · {formatKm(drawing.lengthOutM)}</strong>
              <span>
                {waypoints.length > 0 ? `${waypoints.length} of ${MAX_WAYPOINTS} waypoints` : 'No waypoints'} ·{' '}
                {startName || drawing.startName} → {endName || drawing.endName}
              </span>
            </span>
          </div>
          <div>
            <span className="dir" aria-hidden="true">
              ←
            </span>
            <span>
              <strong>Way back · {formatKm(drawing.lengthBackM)}</strong>
              <span>
                {backWaypoints.length > 0 ? `${backWaypoints.length} of ${MAX_WAYPOINTS} waypoints` : 'The fastest'}
                {drawing.backVia.length > 0 && ` · uses ${drawing.backVia.join(' and ')}`}
              </span>
            </span>
          </div>
          {drawing.towns.length > 0 && <p>Via {drawing.towns.join(' · ')}</p>}
          {backWaypoints.length > 0 && (
            <button type="button" className="a-btn plain" onClick={() => setBackWaypoints([])}>
              Reset way back to the fastest
            </button>
          )}
        </div>
      )}
      {problems.map((p) => (
        <p key={p} role="alert" className="admin-alert">
          {PROBLEMS[p] ?? p}
        </p>
      ))}
      {error && (
        <p role="alert" className="admin-alert">
          {error}
        </p>
      )}
      <div className="form-actions">
        {ready && !busy && (
          <ReasonAction
            label={editingId ? 'Save the new line' : 'Add this bus route'}
            kind="primary"
            title={editingId ? `Save the new line for bus ${number.trim()}?` : `Add bus ${number.trim()}?`}
            description="It shows on the map after the next scoring run."
            run={save}
          />
        )}
        {!desktop && number.trim() && (
          <button type="button" className="a-btn plain" aria-expanded={details} onClick={() => setDetails(!details)}>
            Details
          </button>
        )}
        <button
          type="button"
          className="a-btn plain"
          onClick={() => {
            setStart(undefined)
            setEnd(undefined)
            setWaypoints([])
            setBackWaypoints([])
            setLeg('out')
          }}
        >
          Start again
        </button>
      </div>
    </section>
  )
}
