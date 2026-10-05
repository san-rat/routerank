import { type DragEvent, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { api, ApiError, type Problem, type RouteView } from '../api/client'
import clock from '../assets/icons/clock-12.svg'
import dots from '../assets/icons/dots.svg'
import downDisabled from '../assets/icons/down-disabled.svg'
import down from '../assets/icons/down.svg'
import edit from '../assets/icons/edit.svg'
import mapIcon from '../assets/icons/map-20.svg'
import plus from '../assets/icons/plus.svg'
import swap from '../assets/icons/swap.svg'
import trash from '../assets/icons/trash.svg'
import upDisabled from '../assets/icons/up-disabled.svg'
import up from '../assets/icons/up.svg'
import { loadAccount, signedIn, useAccount } from '../auth/account'
import { SignInButton } from '../auth/SignInButton'
import { Dialog, LockedDialog } from './common'
import { draftActions } from './draft'
import { formatKm } from './geometry'
import { formatWait, loadMyRoutes, lockedUntil, placeInSlot, POINTS, setMyRoutes, SLOTS, useMyRoutes } from './myRoutes'

/** /me/routes — the user's three slots (W25–W28) */
export function MyRoutesPage() {
  const account = useAccount()
  useEffect(() => {
    // Anonymous visitors never call the API: this only asks it if this browser signed in before
    loadAccount()
  }, [])
  if (account.kind === 'signed-in') return <MyRoutesList initials={initials(account.me.email)} />
  return <SignedOut loading={account.kind === 'loading'} />
}

function initials(email: string) {
  const name = email.split('@')[0].replace(/[^a-zA-Z]+/g, ' ').trim().split(' ')
  return ((name[0]?.[0] ?? '') + (name[1]?.[0] ?? '')).toUpperCase() || '?'
}

/** W28 */
function SignedOut({ loading }: { loading: boolean }) {
  return (
    <main className="page my-routes">
      <header className="my-routes-header">
        <h1>My routes</h1>
      </header>
      <div className="slots" aria-hidden="true">
        {SLOTS.map((s) => (
          <div key={s} className="slot-card skeleton">
            <span className="slot-badge empty">#{s}</span>
            <span className="skeleton-line" />
            <span className="points-chip">+{POINTS[s]} {POINTS[s] === 1 ? 'pt' : 'pts'}</span>
          </div>
        ))}
      </div>
      <section className="signed-out-intro">
        <h2>Pick up to 3 routes</h2>
        <p>Sign in to choose where new buses should run. Rank them #1 to #3 — you can change them any time.</p>
        {!loading && <SignInButton onSignedIn={signedIn} />}
      </section>
    </main>
  )
}

function MyRoutesList({ initials }: { initials: string }) {
  const state = useMyRoutes()
  const navigate = useNavigate()
  const [actions, setActions] = useState<RouteView | null>(null)
  const [choosingSlot, setChoosingSlot] = useState<RouteView | null>(null)
  const [removing, setRemoving] = useState<RouteView | null>(null)
  const [locked, setLocked] = useState<Problem | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const [dragging, setDragging] = useState<number | null>(null)

  useEffect(() => {
    loadMyRoutes()
    // Keep the countdowns current
    const timer = setInterval(() => setNow(Date.now()), 60_000)
    return () => clearInterval(timer)
  }, [])

  if (state.kind === 'loading') return <main className="page my-routes"><p>Loading…</p></main>
  if (state.kind === 'error' || state.kind === 'signed-out') {
    return (
      <main className="page my-routes">
        <p role="alert">Couldn't load your routes. Please try again later.</p>
      </main>
    )
  }
  const { routes, slots, countsFrom } = state.data
  const counting = new Date(countsFrom).getTime() <= now

  async function change(run: () => Promise<unknown>) {
    setError(null)
    try {
      await run()
    } catch (e) {
      const lock = e instanceof ApiError ? e.problems.find((p) => p.code === 'SLOT_LOCKED') : undefined
      if (lock) setLocked(lock)
      else setError(e instanceof ApiError && e.status === 429 ? 'Too many changes in a short time. Try again in a few minutes.' : "Couldn't save that change. Please try again.")
    }
    await loadMyRoutes()
  }

  /** Moves a route to a slot the way the API places it, then saves the whole order */
  function moveTo(route: RouteView, slot: number) {
    const place = placeInSlot(routes, route.id, slot)
    if (!place) return
    const order = [...place.entries()].map(([id, s]) => ({ routeId: id === -1 ? route.id : id, slot: s }))
    change(async () => setMyRoutes(await api.reorderRoutes(order)))
  }

  /** Desktop: drop a route on another slot to move it there */
  function dropTarget(slot: number) {
    return {
      onDragOver: (e: DragEvent) => dragging !== null && e.preventDefault(),
      onDrop: (e: DragEvent) => {
        e.preventDefault()
        const route = routes.find((r) => r.id === dragging)
        setDragging(null)
        if (route && route.slot !== slot) moveTo(route, slot)
      },
    }
  }

  function editRoute(route: RouteView) {
    draftActions.edit(route, route)
    navigate('/add/route')
  }

  // The user's best stretch rank: their routes' busiest stretches are their best-ranked ones
  const ranks = routes.map((r) => r.busiest?.rank).filter((r): r is number => typeof r === 'number')
  const best = ranks.length > 0 ? Math.min(...ranks) : null

  return (
    <main className="page my-routes">
      <header className="my-routes-header">
        <div>
          <h1>My routes</h1>
          <p>
            {routes.length} of 3 slots used{best ? ` · best stretch #${best}` : ''}
            {routes.length > 1 ? ' · tap ↑ ↓ to reorder' : ''}
          </p>
        </div>
        <span className="avatar large" aria-hidden="true">
          {initials}
        </span>
      </header>
      {error && <p role="alert">{error}</p>}
      <ol className="slots">
        {SLOTS.map((s) => {
          const route = routes.find((r) => r.slot === s)
          const until = lockedUntil(slots.find((v) => v.slot === s), now)
          if (!route) {
            return (
              <li key={s} {...dropTarget(s)}>
                <button
                  type="button"
                  className="slot-card empty"
                  onClick={() => {
                    draftActions.reset(s)
                    navigate('/add')
                  }}
                >
                  <span className="slot-badge add">
                    <img src={plus} alt="" width={20} height={20} />
                  </span>
                  <span className="slot-text">
                    <strong>Add your #{s} route</strong>
                    <span>
                      {until ? `Slot #${s} unlocks in ${formatWait(until, now).text}` : `Worth +${POINTS[s]} ${POINTS[s] === 1 ? 'pt' : 'pts'} on every stretch it covers`}
                    </span>
                  </span>
                </button>
              </li>
            )
          }
          return (
            <li
              key={s}
              className={`slot-card ${dragging === route.id ? 'dragging' : ''}`}
              draggable
              onDragStart={(e) => {
                e.dataTransfer.effectAllowed = 'move'
                setDragging(route.id)
              }}
              onDragEnd={() => setDragging(null)}
              {...dropTarget(s)}
            >
              <span className="reorder">
                <button type="button" disabled={s === 1} onClick={() => moveTo(route, s - 1)} aria-label={`Move ${route.name} up`}>
                  <img src={s === 1 ? upDisabled : up} alt="" width={14} height={14} />
                </button>
                <button type="button" disabled={s === 3} onClick={() => moveTo(route, s + 1)} aria-label={`Move ${route.name} down`}>
                  <img src={s === 3 ? downDisabled : down} alt="" width={14} height={14} />
                </button>
              </span>
              <span className="slot-badge">#{s}</span>
              <span className="slot-text">
                <strong>{route.name}</strong>
                <span>
                  {formatKm(Math.max(route.lengthOutM, route.lengthBackM))}
                  {route.extendsBus && ` · extends bus ${route.extendsBus.number}`}
                </span>
                {route.busiest && (
                  <Link className="busiest" to={`/s/${route.busiest.slug}`}>
                    Busiest stretch: {route.busiest.name} · {route.busiest.points.toLocaleString('en-US')} pts
                    {route.busiest.rank ? ` · #${route.busiest.rank}` : ''}
                  </Link>
                )}
                <span className="chips">
                  <span className="points-chip">+{POINTS[s]} {POINTS[s] === 1 ? 'pt' : 'pts'}</span>
                  {counting ? (
                    <span className="status-chip counting">● Counting</span>
                  ) : (
                    <span className="status-chip pending">
                      <img src={clock} alt="" width={12} height={12} /> Counts in 24 h
                    </span>
                  )}
                  {until && (
                    <span className="status-chip locked" title={`Slot #${s} can change again in ${formatWait(until, now).text}`}>
                      Locked · {formatWait(until, now).text}
                    </span>
                  )}
                </span>
              </span>
              <button type="button" className="round-button" onClick={() => setActions(route)} aria-label={`Actions for ${route.name}`}>
                <img src={dots} alt="" width={20} height={20} />
              </button>
            </li>
          )
        })}
      </ol>
      <p className="footnote">Your routes can’t share road with each other. Each slot can change once every 24 h.</p>

      {actions && (
        <>
          <div className="scrim" onClick={() => setActions(null)} />
          <section className="sheet actions-sheet" role="dialog" aria-modal="true" aria-label={actions.name}>
            <div className="grabber" />
            <div>
              <h2>{actions.name}</h2>
              <p>
                Your #{actions.slot} · {formatKm(Math.max(actions.lengthOutM, actions.lengthBackM))} · {counting ? 'counting' : 'counts in 24 h'}
              </p>
            </div>
            <button type="button" className="action-row" onClick={() => navigate(`/map/western?route=${actions.id}`)}>
              <span className="action-icon">
                <img src={mapIcon} alt="" width={20} height={20} />
              </span>
              <span className="action-text">
                <strong>Show on map</strong>
              </span>
            </button>
            <button type="button" className="action-row" onClick={() => editRoute(actions)}>
              <span className="action-icon">
                <img src={edit} alt="" width={20} height={20} />
              </span>
              <span className="action-text">
                <strong>Edit route</strong>
                <span>Move start, end or waypoints</span>
              </span>
            </button>
            <button
              type="button"
              className="action-row"
              disabled={routes.length < 1}
              onClick={() => {
                setChoosingSlot(actions)
                setActions(null)
              }}
            >
              <span className="action-icon">
                <img src={swap} alt="" width={20} height={20} />
              </span>
              <span className="action-text">
                <strong>Change preference</strong>
                <span>Make it {SLOTS.filter((n) => n !== actions.slot).map((n) => `#${n}`).join(' or ')}</span>
              </span>
            </button>
            <button
              type="button"
              className="action-row danger"
              onClick={() => {
                setRemoving(actions)
                setActions(null)
              }}
            >
              <span className="action-icon red">
                <img src={trash} alt="" width={20} height={20} />
              </span>
              <span className="action-text">
                <strong>Remove route</strong>
                <span>Frees up this slot</span>
              </span>
            </button>
          </section>
        </>
      )}

      {choosingSlot && (
        <Dialog label="Change preference" onClose={() => setChoosingSlot(null)}>
          <h2>Make {choosingSlot.name}…</h2>
          <div className="slot-choices">
            {SLOTS.filter((n) => n !== choosingSlot.slot).map((n) => (
              <button
                key={n}
                type="button"
                className="secondary-button wide"
                onClick={() => {
                  moveTo(choosingSlot, n)
                  setChoosingSlot(null)
                }}
              >
                Your #{n} (+{POINTS[n]} {POINTS[n] === 1 ? 'pt' : 'pts'})
              </button>
            ))}
          </div>
          <button type="button" className="text-button" onClick={() => setChoosingSlot(null)}>
            Cancel
          </button>
        </Dialog>
      )}

      {removing && (
        <Dialog label="Remove this route?" onClose={() => setRemoving(null)}>
          <span className="dialog-icon red">
            <img src={trash} alt="" width={24} height={24} />
          </span>
          <h2>Remove this route?</h2>
          <p>
            {removing.name}’s {POINTS[removing.slot]} {POINTS[removing.slot] === 1 ? 'point comes' : 'points come'} off at the next update
            (within 30 min).
            {lockedUntil(slots.find((v) => v.slot === removing.slot)) && ` Slot #${removing.slot} stays locked until its 24-hour timer ends.`}
          </p>
          <button
            type="button"
            className="danger-button"
            onClick={() => {
              const id = removing.id
              setRemoving(null)
              change(() => api.removeRoute(id))
            }}
          >
            Remove route
          </button>
          <button type="button" className="secondary-button wide soft" onClick={() => setRemoving(null)}>
            Cancel
          </button>
        </Dialog>
      )}

      {locked && <LockedDialog problem={locked} onClose={() => setLocked(null)} />}
    </main>
  )
}
