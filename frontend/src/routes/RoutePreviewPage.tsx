import { useEffect, useMemo, useState } from 'react'
import { Navigate, useNavigate } from 'react-router'
import { useMap } from '../app/mapContext'
import { DESKTOP, useMediaQuery } from '../app/useMediaQuery'
import alertAmber from '../assets/icons/alert-amber.svg'
import back from '../assets/icons/back.svg'
import info from '../assets/icons/info-16.svg'
import ruler from '../assets/icons/ruler.svg'
import swapAmber from '../assets/icons/swap-amber.svg'
import swapTeal from '../assets/icons/swap-teal.svg'
import undo from '../assets/icons/undo.svg'
import { BusCheckSheet } from './BusCheck'
import { SideRoadSheet, SignInGate } from './common'
import { draftActions, ensurePreview, useDraft } from './draft'
import { formatKm } from './geometry'
import { useRouteLayer } from './useRouteLayer'

const MAX_M = 40_000

// Room left for the floating header and the bottom card, so the fitted route stays visible
const MOBILE_FIT = { top: 130, bottom: 380, left: 40, right: 40 }
const DESKTOP_FIT = { top: 60, bottom: 60, left: 60, right: 60 }

/**
 * /add/route — the route on the map, adjusted by dragging; every rule checked before continuing (W17, W21–W23, W33),
 * then the Bus check when it extends a bus route (W18, W18b)
 */
export function RoutePreviewPage() {
  return (
    <SignInGate title="Add a route">
      <RoutePreview />
    </SignInGate>
  )
}

function RoutePreview() {
  const draft = useDraft()
  const map = useMap()
  const desktop = useMediaQuery(DESKTOP)
  const navigate = useNavigate()
  const preview = draft.preview
  // After "Reroute around it" the overlap shows as a note instead of a sheet, while the user drags
  const [rerouting, setRerouting] = useState(false)
  // Continue opens the Bus check when the route extends a bus route
  const [choosing, setChoosing] = useState(false)

  useEffect(() => {
    ensurePreview()
  }, [])

  const points = preview?.points
  const drawing = useMemo(
    () => ({
      out: preview?.out ?? [],
      backLeaves: preview?.backLeaves ?? [],
      // Pins sit on the snapped points once the route is worked out
      start: points?.[0] ?? draft.start,
      end: points?.[points.length - 1] ?? draft.end,
      waypoints: draft.waypoints,
    }),
    [preview, points, draft.start, draft.end, draft.waypoints],
  )
  useRouteLayer(
    map,
    drawing,
    {
      onLineClick: (p) => draftActions.addWaypoint(p),
      onMovePoint: (which, p) => draftActions.movePoint(which, p),
      onMoveWaypoint: (i, p) => draftActions.moveWaypoint(i, p),
      onRemoveWaypoint: (i) => draftActions.removeWaypoint(i),
    },
    { key: `${draft.start?.lat},${draft.start?.lon}-${draft.end?.lat},${draft.end?.lon}-${preview ? 1 : 0}`, padding: desktop ? DESKTOP_FIT : MOBILE_FIT },
  )

  if (!draft.start || !draft.end) return <Navigate to="/add" replace />

  const problems = preview?.problems ?? []
  const pointProblem = problems.find((p) => p.code === 'SIDE_ROAD' || p.code === 'NO_ROAD_NEARBY')
  const overlap = problems.find((p) => p.code === 'OVERLAP')
  const longer = preview ? Math.max(preview.lengthOutM, preview.lengthBackM) : 0
  const bus = preview?.bus
  // Saving it as an extension can work even where saving it as a new route can't (an overlap on the bus's road)
  const canExtend = !!bus && bus.problems.length === 0
  const ok = preview && (problems.length === 0 || canExtend) && !draft.previewing
  const name = preview?.name || draft.editing?.name || 'Your route'

  function leave() {
    navigate('/add')
  }

  function next() {
    if (bus) {
      setChoosing(true)
      return
    }
    draftActions.setExtend(undefined)
    navigate('/add/rank')
  }

  let card
  if (choosing && preview && bus && !draft.previewing) {
    card = (
      <BusCheckSheet
        key={`${bus.busRouteId}-${bus.totalM}`}
        preview={preview}
        bus={bus}
        onChoose={(extend) => {
          draftActions.setExtend(extend)
          navigate('/add/rank')
        }}
      />
    )
  } else if (!preview && draft.previewFailed) {
    card = (
      <section className="route-sheet">
        <p role="alert">Couldn't work out the route.</p>
        <button type="button" className="primary-button" onClick={draftActions.retry}>
          Try again
        </button>
      </section>
    )
  } else if (!preview) {
    card = (
      <section className="route-sheet" aria-busy="true">
        <p>Working out the route…</p>
      </section>
    )
  } else if (pointProblem?.code === 'SIDE_ROAD' && pointProblem.nearest && (pointProblem.point === 'start' || pointProblem.point === 'end')) {
    const which = pointProblem.point
    card = (
      <SideRoadSheet
        input={draft[which]}
        nearest={pointProblem.nearest}
        onUse={() => draftActions.useNearest(which, pointProblem.nearest!)}
        onPickAnother={leave}
      />
    )
  } else if (pointProblem || problems.some((p) => p.code === 'NO_ROUTE')) {
    card = (
      <section className="route-sheet">
        <div className="alert-header">
          <span className="alert-icon amber">
            <img src={alertAmber} alt="" width={24} height={24} />
          </span>
          <div>
            <h2>Can't join these points on main roads</h2>
            <p>Move a point onto a main road that connects to the rest.</p>
          </div>
        </div>
        <button type="button" className="primary-button" onClick={leave}>
          Change start or end
        </button>
      </section>
    )
  } else if (problems.some((p) => p.code === 'NO_WAY_BACK')) {
    card = (
      <section className="route-sheet" aria-label="No way back on main roads">
        <div className="grabber" />
        <Distance metres={longer} />
        <div className="alert-header">
          <span className="alert-icon amber">
            <img src={swapAmber} alt="" width={24} height={24} />
          </span>
          <div>
            <h2>No way back on main roads</h2>
            <p>The streets at this end are one-way, so the only way back out is on side roads. Move the end point to a nearby main road.</p>
          </div>
        </div>
        <button
          type="button"
          className="primary-button"
          onClick={() => {
            draftActions.clearEnd()
            navigate('/add')
          }}
        >
          Move end point
        </button>
      </section>
    )
  } else if (overlap && !rerouting && !canExtend) {
    card = (
      <section className="route-sheet" aria-label={`This overlaps your route #${overlap.slot}`}>
        <div className="grabber" />
        <div className="alert-header">
          <span className="alert-icon amber">
            <img src={alertAmber} alt="" width={24} height={24} />
          </span>
          <div>
            <h2>This overlaps your route #{overlap.slot}</h2>
            <p>Your 3 routes can’t share any road. Crossing at a junction is fine.</p>
          </div>
        </div>
        <div className="overlap-route">
          <span className="slot-badge small">#{overlap.slot}</span>
          <div>
            <strong>{overlap.routeName}</strong>
            <span>Your route #{overlap.slot}</span>
          </div>
        </div>
        <button
          type="button"
          className="primary-button"
          onClick={() => setRerouting(true)}
        >
          Reroute around it
        </button>
        <button
          type="button"
          className="secondary-button wide soft"
          onClick={() => draftActions.edit({ id: overlap.routeId!, slot: overlap.slot!, name: overlap.routeName! })}
        >
          Replace route #{overlap.slot} with this one
        </button>
      </section>
    )
  } else {
    const tooLong = problems.find((p) => p.code === 'TOO_LONG')
    card = (
      <section className="route-sheet" aria-label="Your route">
        <div className="grabber" />
        <Distance metres={longer} />
        {tooLong ? (
          <div className="alert-header">
            <span className="alert-icon amber">
              <img src={ruler} alt="" width={24} height={24} />
            </span>
            <div>
              <h2>{formatKm(longer - MAX_M)} over the limit</h2>
              <p>RouteRank is for metro buses, so routes max out at 40 km. Move the end point closer or reroute.</p>
            </div>
          </div>
        ) : (
          <>
            {preview.backLeaves.length > 0 && (
              <p className="note teal">
                <img src={swapTeal} alt="" width={20} height={20} />
                <span>
                  {preview.backVia.length > 0 ? `Way back uses ${preview.backVia.join(' and ')}` : 'Way back differs'} (one-way
                  streets) · dashed line, {formatKm(preview.lengthBackM)}
                </span>
              </p>
            )}
            {overlap && (
              <p className="note amber">
                <img src={alertAmber} alt="" width={20} height={20} />
                <span>Still shares road with your #{overlap.slot}. Drag the line away from it.</span>
              </p>
            )}
          </>
        )}
        <p className="hint">
          <img src={info} alt="" width={16} height={16} /> Drag the pins to reroute · tap the line to add a waypoint · tap a waypoint to remove it
        </p>
        <button type="button" className="primary-button" disabled={!ok} onClick={next}>
          {draft.previewing ? 'Checking…' : 'Continue'}
        </button>
      </section>
    )
  }

  return (
    <div className="add-route">
      <header className="route-header">
        <button type="button" className="round-button" onClick={leave} aria-label="Back">
          <img src={back} alt="" width={20} height={20} />
        </button>
        <button type="button" className="route-header-title" onClick={leave}>
          <strong>{name}</strong>
          <span>Edit start or end</span>
        </button>
        <button type="button" className="round-button" onClick={draftActions.undo} disabled={draft.history.length === 0} aria-label="Undo">
          <img src={undo} alt="" width={20} height={20} />
        </button>
      </header>
      {card}
    </div>
  )
}

/** "36.8 km of 40 km max" and the bar, amber past the cap (W17, W21) */
function Distance({ metres }: { metres: number }) {
  const over = metres > MAX_M
  const within = Math.min(metres, MAX_M) / Math.max(metres, MAX_M)
  return (
    <>
      <p className={`distance ${over ? 'over' : ''}`}>
        <strong>{formatKm(metres)}</strong> of 40 km max
      </p>
      <div className="distance-bar" role="meter" aria-valuemin={0} aria-valuemax={MAX_M} aria-valuenow={Math.round(metres)} aria-label="Length of the longer direction">
        <span style={{ width: `${within * 100}%` }} />
        {over && <span className="over" style={{ width: `${(1 - within) * 100}%` }} />}
      </div>
    </>
  )
}

