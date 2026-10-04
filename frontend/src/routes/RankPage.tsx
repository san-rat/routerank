import { useEffect, useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router'
import { api, ApiError, type Problem, type RouteView } from '../api/client'
import back from '../assets/icons/back.svg'
import check from '../assets/icons/check-white.svg'
import clock from '../assets/icons/clock-12.svg'
import info from '../assets/icons/info-16.svg'
import mapIcon from '../assets/icons/map-white.svg'
import routeIcon from '../assets/icons/route-teal.svg'
import { deviceSignal, turnstileToken } from '../fraud/botCheck'
import { LockedDialog, SignInGate } from './common'
import { blockingProblems, draftActions, useDraft } from './draft'
import { formatKm } from './geometry'
import { formatWait, loadMyRoutes, lockedUntil, placeInSlot, POINTS, SLOTS, useMyRoutes } from './myRoutes'

const dayFormat = new Intl.DateTimeFormat('en-LK', { weekday: 'short', day: 'numeric', month: 'short' })

/** /add/rank — choose the slot and save; then "Your vote is in" (W19, W20, W24) */
export function RankPage() {
  return (
    <SignInGate title="Rank your route">
      <Rank />
    </SignInGate>
  )
}

function Rank() {
  const draft = useDraft()
  const myRoutes = useMyRoutes()
  const navigate = useNavigate()
  const [slot, setSlot] = useState<number | null>(draft.editing?.slot ?? draft.preferredSlot ?? null)
  const [saving, setSaving] = useState(false)
  const [locked, setLocked] = useState<Problem | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState<RouteView | null>(null)
  // Hidden from people (and screen readers); only a bot fills it in
  const [website, setWebsite] = useState('')

  useEffect(() => {
    loadMyRoutes()
  }, [])

  if (saved) return <Saved route={saved} countsFrom={myRoutes.kind === 'loaded' ? myRoutes.data.countsFrom : undefined} />
  const preview = draft.preview
  if (!preview || blockingProblems(draft).length > 0 || !draft.start || !draft.end) return <Navigate to="/add/route" replace />
  // An extension is named by the whole extended route ("Pettah → Horana")
  const name = draft.extend !== undefined && preview.bus ? preview.bus.name : preview.name
  if (myRoutes.kind !== 'loaded') {
    return <main className="page">{myRoutes.kind === 'error' ? <p role="alert">Couldn't load your routes.</p> : <p>Loading…</p>}</main>
  }

  const { routes, slots } = myRoutes.data
  const editingId = draft.editing?.id ?? null
  const placement = slot === null ? null : placeInSlot(routes, editingId, slot)
  const moved = placement
    ? routes.filter((r) => r.id !== editingId && placement.get(r.id) !== undefined && placement.get(r.id) !== r.slot)
    : []

  function optionFor(s: number) {
    const place = placeInSlot(routes, editingId, s)
    if (!place) return { disabled: true, reason: 'All slots hold other routes' }
    // Every slot that gets a different route must be free to change
    const touched = [...place.entries()].filter(([id, to]) => id === -1 || id === editingId || routes.find((r) => r.id === id)?.slot !== to)
    for (const [, to] of touched) {
      const until = lockedUntil(slots.find((v) => v.slot === to))
      if (until) return { disabled: true, reason: `#${to} locked · ${formatWait(until).text}` }
    }
    return { disabled: false, reason: null }
  }

  async function save() {
    if (slot === null) return
    setSaving(true)
    setError(null)
    try {
      const [turnstile, device] = await Promise.all([turnstileToken('save'), deviceSignal()])
      const input = {
        start: draft.start!,
        end: draft.end!,
        waypoints: draft.waypoints,
        slot,
        extend: draft.extend,
        turnstile,
        device,
        website: website || undefined,
      }
      const route = editingId ? await api.updateRoute(editingId, input) : await api.createRoute(input)
      await loadMyRoutes()
      setSaved(route)
      draftActions.reset()
    } catch (e) {
      const problems = e instanceof ApiError ? e.problems : []
      const lock = problems.find((p) => p.code === 'SLOT_LOCKED')
      if (lock) setLocked(lock)
      else if (problems.length > 0) navigate('/add/route') // the route changed under us (e.g. another tab); re-check it
      else if (e instanceof ApiError && e.status === 429) setError('Too many changes in a short time. Try again in a few minutes.')
      else if (!(e instanceof ApiError) || e.code?.startsWith('bot_check')) setError("Couldn't check that you're not a bot. Please try again.")
      else setError("Couldn't save. Please try again.")
      await loadMyRoutes()
    } finally {
      setSaving(false)
    }
  }

  const occupant = (s: number) => routes.find((r) => r.slot === s && r.id !== editingId)
  return (
    <main className="page rank-page">
      <header className="page-header">
        <button type="button" className="round-button" onClick={() => navigate('/add/route')} aria-label="Back">
          <img src={back} alt="" width={20} height={20} />
        </button>
        <h1>Rank this route</h1>
      </header>
      <div className="route-summary">
        <span className="route-summary-icon">
          <img src={routeIcon} alt="" width={22} height={22} />
        </span>
        <div>
          <strong>{name}</strong>
          <span>
            {draft.extend !== undefined && preview.bus
              ? `Extends bus ${preview.bus.number} · ${formatKm(preview.bus.newLengthM)} new earns points`
              : formatKm(Math.max(preview.lengthOutM, preview.lengthBackM))}
          </span>
        </div>
      </div>
      <h2 className="section-label">Pick a slot</h2>
      <div className="slot-options" role="radiogroup" aria-label="Slot">
        {SLOTS.map((s) => {
          const option = optionFor(s)
          const current = occupant(s)
          const selected = slot === s
          return (
            <button
              key={s}
              type="button"
              role="radio"
              aria-checked={selected}
              disabled={option.disabled}
              className={`slot-option ${selected ? 'selected' : ''}`}
              onClick={() => setSlot(s)}
            >
              <span className="slot-option-row">
                <span className={`slot-badge ${selected ? 'teal' : 'empty'}`}>#{s}</span>
                <span className="slot-option-text">
                  <strong>{selected ? name : current ? current.name : 'Empty slot'}</strong>
                  {option.reason ? (
                    <span className="locked-text">
                      <img src={clock} alt="" width={12} height={12} /> {option.reason}
                    </span>
                  ) : selected && current ? (
                    <span>New · replaces your current #{s}</span>
                  ) : selected && editingId && draft.editing?.slot === s ? (
                    <span>Edited · stays #{s}</span>
                  ) : null}
                </span>
                <span className="points-chip">+{POINTS[s]} {POINTS[s] === 1 ? 'pt' : 'pts'}</span>
              </span>
              {selected &&
                moved.map((r) => (
                  <span key={r.id} className="moves-note">
                    ↓ {r.name} moves to #{placement!.get(r.id)}
                  </span>
                ))}
            </button>
          )
        })}
      </div>
      <p className="hint">
        <img src={info} alt="" width={16} height={16} /> Each slot can be changed once every 24 hours.
      </p>
      <label className="honeypot" aria-hidden="true">
        Website
        <input type="text" name="website" tabIndex={-1} autoComplete="off" value={website} onChange={(e) => setWebsite(e.target.value)} />
      </label>
      {error && <p role="alert">{error}</p>}
      <button type="button" className="primary-button sticky" disabled={slot === null || saving || optionFor(slot ?? 1).disabled} onClick={save}>
        {saving ? 'Saving…' : 'Save my vote'}
      </button>
      {locked && <LockedDialog problem={locked} onClose={() => setLocked(null)} />}
    </main>
  )
}

/** W20: saved, with when it starts counting */
function Saved({ route, countsFrom }: { route: RouteView; countsFrom?: string }) {
  const [now] = useState(() => Date.now())
  const pending = countsFrom && new Date(countsFrom).getTime() > now
  return (
    <main className="page saved-page">
      <div className="saved-hero">
        <span className="saved-check">
          <img src={check} alt="" width={36} height={36} />
        </span>
        <h1>Your vote is in!</h1>
        <p>
          {route.name} is now your #{route.slot}.<br />+{POINTS[route.slot]} {POINTS[route.slot] === 1 ? 'point' : 'points'} on{' '}
          {formatKm(route.lengthOutM)} of road.
        </p>
      </div>
      {pending && (
        <div className="notice amber">
          <img src={clock} alt="" width={16} height={16} />
          <div>
            <strong>Starts counting in 24 hours</strong>
            <span>Votes from new accounts start counting after 24 hours — yours on {dayFormat.format(new Date(countsFrom!))}.</span>
          </div>
        </div>
      )}
      <div className="saved-actions">
        <Link className="primary-button" to={`/map/western?route=${route.id}`}>
          <img src={mapIcon} alt="" width={20} height={20} /> See it on the map
        </Link>
        <Link className="secondary-button wide soft" to="/me/routes">
          View my routes
        </Link>
      </div>
    </main>
  )
}
