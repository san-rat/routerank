import { type ReactNode, useEffect } from 'react'
import type { LatLon, Problem } from '../api/client'
import alertRed from '../assets/icons/alert-red.svg'
import clock from '../assets/icons/clock-12.svg'
import road from '../assets/icons/road.svg'
import { loadAccount, signedIn, useAccount } from '../auth/account'
import { SignInButton } from '../auth/SignInButton'
import { distanceM, formatKm } from './geometry'
import { formatWait } from './myRoutes'

/** Shows the page only to signed-in users; others get the Google button (W14) */
export function SignInGate({ title, children }: { title: string; children: ReactNode }) {
  const account = useAccount()
  useEffect(() => {
    loadAccount({ verify: true })
  }, [])
  if (account.kind === 'signed-in') return <>{children}</>
  return (
    <main className="page sign-in-page">
      <h1>{title}</h1>
      {account.kind === 'loading' && <p>Loading…</p>}
      {account.kind === 'error' && <p role="alert">Couldn't reach RouteRank. Please try again later.</p>}
      {account.kind === 'signed-out' && (
        <>
          <p>Sign in with Google to add your routes. RouteRank keeps only your Google account ID and email.</p>
          <SignInButton onSignedIn={signedIn} />
        </>
      )}
    </main>
  )
}

/** A centred card over a dimmed page (W24, W27) */
export function Dialog({ label, children, onClose }: { label: string; children: ReactNode; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <>
      <div className="scrim" onClick={onClose} />
      <div className="dialog" role="dialog" aria-modal="true" aria-label={label}>
        {children}
      </div>
    </>
  )
}

/** W24: a slot changed in the last 24 hours */
export function LockedDialog({ problem, onClose }: { problem: Problem; onClose: () => void }) {
  const wait = problem.until ? formatWait(new Date(problem.until)) : null
  return (
    <Dialog label={`Slot #${problem.slot} is locked`} onClose={onClose}>
      <span className="dialog-icon amber">
        <img src={clock} alt="" width={24} height={24} />
      </span>
      <h2>Slot #{problem.slot} is locked for now</h2>
      <p>Each slot can change once every 24 hours — it keeps the rankings steady.</p>
      {wait && (
        <div className="countdown" aria-label={`Unlocks in ${wait.text}`}>
          <span>
            <strong>{wait.hours}</strong>hours
          </span>
          <span>
            <strong>{wait.minutes}</strong>mins
          </span>
        </div>
      )}
      <button type="button" className="primary-button" onClick={onClose}>
        Got it
      </button>
    </Dialog>
  )
}

/** W22: a start or end point off the main roads, with the nearest main road offered instead */
export function SideRoadSheet({
  input,
  nearest,
  onUse,
  onPickAnother,
}: {
  input?: LatLon
  nearest: LatLon
  onUse: () => void
  onPickAnother: () => void
}) {
  return (
    <section className="route-sheet" aria-label="That's a side road">
      <div className="grabber" />
      <div className="alert-header">
        <span className="alert-icon red">
          <img src={alertRed} alt="" width={24} height={24} />
        </span>
        <div>
          <h2>That’s a side road</h2>
          <p>Buses can only run on main roads, so start and end points must be on one.</p>
        </div>
      </div>
      <div className="suggestion">
        <img src={road} alt="" width={20} height={20} />
        <div>
          <strong>Nearest main road</strong>
          <span>{input ? `${formatKm(distanceM(input, nearest))} away` : 'Close by'}</span>
        </div>
      </div>
      <button type="button" className="primary-button" onClick={onUse}>
        Use the nearest main road
      </button>
      <button type="button" className="text-button" onClick={onPickAnother}>
        Pick another spot
      </button>
    </section>
  )
}
