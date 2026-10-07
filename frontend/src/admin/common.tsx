import { type ReactNode, use, useEffect, useId, useState } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router'
import type { FlaggedAccount } from '../api/client'
import { Chip } from './icons'
import { voteLine } from './format'
import { AdminSessionContext, useAdminError } from './session'

export type Kind = 'primary' | 'secondary' | 'danger' | 'plain'

/**
 * An admin action with its written reason (A02): the button opens a dialog (a bottom sheet on phones), and the
 * action runs only with a reason, which goes in the audit log.
 *
 * @param title the dialog's question, "Release all 3 accounts?" (default: the label and a question mark)
 * @param description what will happen
 * @param confirmLabel the dialog's button, "Remove 4 votes" (default: the label)
 */
export function ReasonAction({ label, kind = 'secondary', title, description, confirmLabel, onDone, run }: {
  label: string
  kind?: Kind
  title?: string
  description?: string
  confirmLabel?: string
  run: (reason: string) => Promise<unknown>
  onDone?: () => void
}) {
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const describe = useAdminError()
  const { changed } = use(AdminSessionContext)
  const titleId = useId()

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !busy) setOpen(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, busy])

  async function submit() {
    if (!reason.trim()) return
    setBusy(true)
    setError(null)
    try {
      await run(reason.trim())
      setOpen(false)
      setReason('')
      changed()
      onDone?.()
    } catch (err) {
      setError(describe(err))
    } finally {
      setBusy(false)
    }
  }

  // Confirming is the dialog's own button: danger stays danger, anything else is the main action
  const confirmKind = kind === 'danger' ? 'danger' : 'primary'
  return (
    <>
      <button type="button" className={`a-btn ${kind}`} onClick={() => setOpen(true)}>
        {label}
      </button>
      {open &&
        createPortal(
          <div className="reason-overlay" onClick={() => !busy && setOpen(false)}>
            <form
              className="reason-dialog"
              role="dialog"
              aria-modal="true"
              aria-labelledby={titleId}
              onClick={(e) => e.stopPropagation()}
              onSubmit={(e) => {
                e.preventDefault()
                submit()
              }}
            >
              <div className="grabber" aria-hidden="true" />
              <h2 id={titleId}>{title ?? `${label}?`}</h2>
              {description && <p>{description}</p>}
              <label>
                Reason
                <textarea value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} rows={3} required autoFocus />
                <span className="hint">Goes in the audit log with your account. Required.</span>
              </label>
              {error && (
                <p role="alert" className="admin-alert">
                  {error}
                </p>
              )}
              <div className="reason-actions">
                <button type="button" className="a-btn plain" onClick={() => setOpen(false)} disabled={busy}>
                  Cancel
                </button>
                <button type="submit" className={`a-btn ${confirmKind}`} disabled={busy || !reason.trim()}>
                  {busy ? 'Working…' : (confirmLabel ?? label)}
                </button>
              </div>
            </form>
          </div>,
          document.body,
        )}
    </>
  )
}

/** A page's title, what it's for, and its main action */
export function PageHeader({ title, children, actions }: { title: string; children?: ReactNode; actions?: ReactNode }) {
  return (
    <header className="admin-header">
      <div>
        <h1>{title}</h1>
        {children && <p>{children}</p>}
      </div>
      {actions && <div className="admin-actions">{actions}</div>}
    </header>
  )
}

/** Nothing to show, said plainly */
export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="admin-empty">
      <strong>{title}</strong>
      {children && <span>{children}</span>}
    </div>
  )
}

const SIGNALS: Record<string, string> = { shared_device: 'shared device' }

/**
 * Flagged accounts as a table (A01, A03): on desktop, columns for the account, its trust score, its signals and its
 * votes; on phones, one card-like block each. {@code action} adds a button per account.
 */
export function AccountTable({ accounts, meta, action }: {
  accounts: FlaggedAccount[]
  meta: (a: FlaggedAccount) => string
  action?: (a: FlaggedAccount) => ReactNode
}) {
  return (
    <div className={`account-table${action ? ' with-action' : ''}`}>
      <div className="account-cols" aria-hidden="true">
        <span>Account</span>
        <span>Trust</span>
        <span>Signals</span>
        <span>Votes</span>
      </div>
      {accounts.map((a) => (
        <div key={a.id} className="account-row">
          <div className="account-who">
            <Link to={`/admin/accounts/${a.id}`}>{a.email}</Link>
            <span>{meta(a)}</span>
          </div>
          <div className="account-chips">
            <Chip>Trust {a.trustScore}</Chip>
          </div>
          <div className="account-chips">
            {a.reasons.map((r) => (
              <Chip key={r} tone="amber">
                {SIGNALS[r] ?? r}
              </Chip>
            ))}
            {a.bannedAt && <Chip tone="red">Banned</Chip>}
          </div>
          <ul className="account-votes">
            {a.routes.length === 0 && <li className="none">No votes</li>}
            {a.routes.map((r) => (
              <li key={r.id} className={r.removedAt ? 'removed' : undefined}>
                {voteLine(r)}
                {r.removedAt && ' · removed'}
              </li>
            ))}
          </ul>
          {action && <div className="account-action">{action(a)}</div>}
        </div>
      ))}
    </div>
  )
}
