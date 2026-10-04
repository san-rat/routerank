import { type ReactNode, useState } from 'react'
import type { RouteSummary } from '../api/client'
import { formatKm } from '../routes/geometry'
import { useAdminError, when } from './session'

/**
 * An admin action with its written reason: the button opens a reason box, and the action runs only with a reason
 * (it goes in the audit log).
 */
export function ReasonAction({ label, danger, onDone, run }: {
  label: string
  danger?: boolean
  run: (reason: string) => Promise<unknown>
  onDone?: () => void
}) {
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const describe = useAdminError()

  if (!open) {
    return (
      <button type="button" className={`admin-button${danger ? ' danger' : ''}`} onClick={() => setOpen(true)}>
        {label}
      </button>
    )
  }
  return (
    <form
      className="reason-form"
      onSubmit={async (e) => {
        e.preventDefault()
        if (!reason.trim()) return
        setBusy(true)
        setError(null)
        try {
          await run(reason.trim())
          setOpen(false)
          setReason('')
          onDone?.()
        } catch (err) {
          setError(describe(err))
        } finally {
          setBusy(false)
        }
      }}
    >
      <label>
        {label}: reason (goes in the audit log)
        <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} required autoFocus />
      </label>
      <span className="reason-actions">
        <button type="submit" className={`admin-button${danger ? ' danger' : ''}`} disabled={busy || !reason.trim()}>
          {busy ? 'Working…' : `Confirm: ${label.toLowerCase()}`}
        </button>
        <button type="button" className="admin-button plain" onClick={() => setOpen(false)}>
          Cancel
        </button>
      </span>
      {error && <p role="alert">{error}</p>}
    </form>
  )
}

/** An account's votes, removed ones struck through */
export function RouteList({ routes, actions }: { routes: RouteSummary[]; actions?: (r: RouteSummary) => ReactNode }) {
  if (routes.length === 0) return <p className="muted">No votes.</p>
  return (
    <ul className="admin-routes">
      {routes.map((r) => (
        <li key={r.id} className={r.removedAt ? 'removed' : undefined}>
          <span>
            #{r.slot} {r.name} · {formatKm(r.lengthM)}
            {r.kind === 'extension' && ` · extends bus ${r.busNumber}`}
            {r.removedAt && ` · removed ${when(r.removedAt)}`}
          </span>
          {actions?.(r)}
        </li>
      ))}
    </ul>
  )
}
