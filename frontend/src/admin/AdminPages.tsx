import { useCallback, useEffect, useState } from 'react'
import { Link, NavLink, Outlet, useMatch, useParams } from 'react-router'
import { adminApi, ApiError, type AccountDetail, type AccountRow, type AuditEntry, type Cluster, type FlaggedAccount } from '../api/client'
import { DESKTOP, useMediaQuery } from '../app/useMediaQuery'
import { signedIn } from '../auth/account'
import { SignInButton } from '../auth/SignInButton'
import { ReasonAction, RouteList } from './common'
import { AdminSessionContext, useAdminError, when } from './session'

type Gate = 'checking' | 'ok' | 'sign-in' | 'forbidden' | 'error'

/**
 * /admin — plain functional pages for admins, linked from nowhere public. Entering needs a Google sign-in from the
 * last 15 minutes; access then lasts while used and ends after 30 minutes idle (the API checks; this shows the
 * sign-in button when it asks).
 */
export function AdminLayout() {
  const [gate, setGate] = useState<Gate>('checking')
  const desktop = useMediaQuery(DESKTOP)
  // On phones, drawing a bus route is a sheet over the map rather than a page
  const drawingOnPhone = useMatch('/admin/buses/:id') !== null && !desktop
  const check = useCallback(() => {
    adminApi.session().then(
      () => setGate('ok'),
      (e: unknown) => setGate(e instanceof ApiError ? (e.status === 401 ? 'sign-in' : e.status === 403 ? 'forbidden' : 'error') : 'error'),
    )
  }, [])
  useEffect(check, [check])
  const expired = useCallback(() => setGate('sign-in'), [])

  if (gate === 'checking') return <main className="page admin" aria-busy="true" />
  if (gate === 'forbidden') {
    return (
      <main className="page admin">
        <h1>Not available</h1>
        <p>This page is only for RouteRank's admins.</p>
      </main>
    )
  }
  if (gate === 'sign-in') {
    return (
      <main className="page admin">
        <h1>Admin</h1>
        <p>Sign in with Google again to use the admin pages. They need a sign-in from the last 15 minutes.</p>
        <SignInButton
          onSignedIn={(me) => {
            signedIn(me)
            setGate('checking')
            check()
          }}
        />
      </main>
    )
  }
  if (gate === 'error') {
    return (
      <main className="page admin">
        <p role="alert">Couldn't reach RouteRank.</p>
        <button
          type="button"
          className="admin-button"
          onClick={() => {
            setGate('checking')
            check()
          }}
        >
          Try again
        </button>
      </main>
    )
  }
  if (drawingOnPhone) {
    return (
      <AdminSessionContext value={{ expired }}>
        <Outlet />
      </AdminSessionContext>
    )
  }
  return (
    <AdminSessionContext value={{ expired }}>
      <main className="page admin">
        <h1>Admin</h1>
        <nav className="admin-tabs" aria-label="Admin">
          <NavLink to="/admin" end>
            Review queue
          </NavLink>
          <NavLink to="/admin/held">Held</NavLink>
          <NavLink to="/admin/accounts">Accounts</NavLink>
          <NavLink to="/admin/buses">Bus routes</NavLink>
          <NavLink to="/admin/audit">Audit log</NavLink>
        </nav>
        <Outlet />
      </main>
    </AdminSessionContext>
  )
}

/** Loads a list for an admin page, with reload after an action */
function useAdminLoad<T>(load: () => Promise<T>) {
  const [data, setData] = useState<T>()
  const [error, setError] = useState<string | null>(null)
  const describe = useAdminError()
  const reload = useCallback(() => {
    load().then(setData, (e: unknown) => setError(describe(e)))
    // describe only reads the context
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [load])
  useEffect(reload, [reload])
  return { data, error, reload }
}

/** /admin — open clusters of flagged accounts, the most suspicious first; release or remove a whole cluster */
export function QueuePage() {
  const { data, error, reload } = useAdminLoad(adminApi.queue)
  return (
    <>
      <section className="admin-section">
        <h2>Scoring</h2>
        <p className="muted">Rankings update at :00 and :30. Run now after adding bus routes or reviewing votes.</p>
        <ReasonAction label="Run scoring now" run={(reason) => adminApi.runScoring(reason)} />
      </section>
      <section className="admin-section">
        <h2>Review queue</h2>
        {error && <p role="alert">{error}</p>}
        {data?.length === 0 && <p className="muted">Nothing to review.</p>}
        {data?.map((c) => <ClusterCard key={c.key} cluster={c} onDone={reload} />)}
      </section>
    </>
  )
}

const REASONS: Record<string, string> = {
  burst: 'New accounts within an hour, same roads',
  shared_device: 'Accounts on one device',
  honeypot: 'Filled in the hidden field (a bot)',
}

function ClusterCard({ cluster, onDone }: { cluster: Cluster; onDone: () => void }) {
  return (
    <article className="admin-card">
      <header>
        <strong>{REASONS[cluster.reason] ?? cluster.reason}</strong>
        <span className="muted">
          {cluster.accounts.length} {cluster.accounts.length === 1 ? 'account' : 'accounts'} · score {cluster.score} · flagged{' '}
          {when(cluster.flaggedAt)} · <code>{cluster.key}</code>
        </span>
      </header>
      {cluster.accounts.map((a) => (
        <AccountLine key={a.id} account={a} />
      ))}
      <div className="admin-actions">
        <ReasonAction label="Release all" run={(r) => adminApi.releaseCluster(cluster.key, r)} onDone={onDone} />
        <ReasonAction label="Remove all their votes" danger run={(r) => adminApi.removeCluster(cluster.key, r)} onDone={onDone} />
      </div>
    </article>
  )
}

function AccountLine({ account }: { account: FlaggedAccount }) {
  return (
    <div className="admin-account">
      <Link to={`/admin/accounts/${account.id}`}>
        {account.email} <span className="muted">#{account.id}</span>
      </Link>
      <span className="muted">
        made {when(account.createdAt)} · trust score {account.trustScore}
        {account.reasons.length > 0 && ` · ${account.reasons.join(', ')}`}
        {account.bannedAt && ' · banned'}
      </span>
      <RouteList routes={account.routes} />
    </div>
  )
}

/** /admin/held — every held account, including ones whose votes were removed */
export function HeldPage() {
  const { data, error, reload } = useAdminLoad(adminApi.held)
  return (
    <section className="admin-section">
      <h2>Held accounts</h2>
      <p className="muted">Their votes stay out of the rankings, and so does anything they save, until released.</p>
      {error && <p role="alert">{error}</p>}
      {data?.length === 0 && <p className="muted">No accounts are held.</p>}
      {data?.map((a) => (
        <article key={a.id} className="admin-card">
          <AccountLine account={a} />
          <div className="admin-actions">
            <ReasonAction label="Release" run={(r) => adminApi.releaseAccount(a.id, r)} onDone={reload} />
          </div>
        </article>
      ))}
    </section>
  )
}

/** /admin/accounts — find an account by email or ID */
export function AccountsPage() {
  const [q, setQ] = useState('')
  const [results, setResults] = useState<AccountRow[]>()
  const [error, setError] = useState<string | null>(null)
  const describe = useAdminError()
  return (
    <section className="admin-section">
      <h2>Accounts</h2>
      <form
        className="admin-search"
        onSubmit={(e) => {
          e.preventDefault()
          adminApi.findAccounts(q).then(setResults, (err: unknown) => setError(describe(err)))
        }}
      >
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Email or account ID" aria-label="Email or account ID" />
        <button type="submit" className="admin-button">
          Find
        </button>
      </form>
      {error && <p role="alert">{error}</p>}
      {results?.length === 0 && <p className="muted">No accounts found.</p>}
      <ul className="admin-list">
        {results?.map((a) => (
          <li key={a.id}>
            <Link to={`/admin/accounts/${a.id}`}>
              {a.email} <span className="muted">#{a.id}</span>
            </Link>
            <span className="muted">
              {a.role === 'admin' && 'admin · '}
              {a.heldAt && 'held · '}
              {a.bannedAt && 'banned · '}made {when(a.createdAt)}
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}

/** /admin/accounts/:id — an account and its votes: release, ban or unban, remove or restore votes */
export function AccountPage() {
  const { id } = useParams()
  const load = useCallback(() => adminApi.account(Number(id)), [id])
  const { data, error, reload } = useAdminLoad<AccountDetail>(load)
  if (error) return <p role="alert">{error}</p>
  if (!data) return <p aria-busy="true">Loading…</p>
  const a = data.account
  return (
    <section className="admin-section">
      <h2>
        {a.email} <span className="muted">#{a.id}</span>
      </h2>
      <p className="muted">
        {a.role} · made {when(a.createdAt)} · votes count from {when(a.liveAt)} · trust score {a.trustScore}
        {a.heldAt && ` · held since ${when(a.heldAt)}`}
        {a.bannedAt && ` · banned ${when(a.bannedAt)}`}
      </p>
      <div className="admin-actions">
        {a.heldAt && <ReasonAction label="Release" run={(r) => adminApi.releaseAccount(a.id, r)} onDone={reload} />}
        {a.role !== 'admin' &&
          (a.bannedAt ? (
            <ReasonAction label="Unban" run={(r) => adminApi.unban(a.id, r)} onDone={reload} />
          ) : (
            <ReasonAction label="Ban" danger run={(r) => adminApi.ban(a.id, r)} onDone={reload} />
          ))}
      </div>
      <h3>Votes</h3>
      <RouteList
        routes={data.routes}
        actions={(r) =>
          r.removedAt ? (
            <ReasonAction label="Restore" run={(reason) => adminApi.restoreRoute(r.id, reason)} onDone={reload} />
          ) : (
            <ReasonAction label="Remove" danger run={(reason) => adminApi.removeRoute(r.id, reason)} onDone={reload} />
          )
        }
      />
    </section>
  )
}

/** /admin/audit — who did what, when and why, newest first */
export function AuditPage() {
  const [entries, setEntries] = useState<AuditEntry[]>([])
  const [more, setMore] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const describe = useAdminError()
  const loadMore = useCallback(
    (before?: number) =>
      adminApi.audit(before).then((page) => {
        setEntries((old) => (before ? [...old, ...page] : page))
        setMore(page.length === 50)
      }, (e: unknown) => setError(describe(e))),
    // describe only reads the context
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  )
  useEffect(() => {
    loadMore()
  }, [loadMore])
  return (
    <section className="admin-section">
      <h2>Audit log</h2>
      {error && <p role="alert">{error}</p>}
      <table className="admin-table">
        <thead>
          <tr>
            <th>When</th>
            <th>Who</th>
            <th>What</th>
            <th>Reason</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((e) => (
            <tr key={e.id}>
              <td>{when(e.at)}</td>
              <td>{e.actorEmail ?? 'database'}</td>
              <td>
                <code>{e.action}</code> {e.target}
                {(e.before || e.after) && (
                  <span className="muted">
                    {' '}
                    {e.before ?? '–'} → {e.after ?? '–'}
                  </span>
                )}
              </td>
              <td>{e.reason}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {more && entries.length > 0 && (
        <button type="button" className="admin-button" onClick={() => loadMore(entries.at(-1)!.id)}>
          Older
        </button>
      )}
    </section>
  )
}
