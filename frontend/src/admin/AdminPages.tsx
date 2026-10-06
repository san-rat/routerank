import { use, useCallback, useEffect, useRef, useState } from 'react'
import { Link, NavLink, Outlet, useLocation, useMatch, useParams } from 'react-router'
import {
  adminApi,
  ApiError,
  type AccountDetail,
  type AccountRow,
  type AuditEntry,
  type Cluster,
  type RouteSummary,
  type ScoringRun,
} from '../api/client'
import logoMark from '../assets/icons/logo-mark.svg'
import { DESKTOP, useMediaQuery } from '../app/useMediaQuery'
import { loadAccount, signedIn, useAccount } from '../auth/account'
import { SignInButton } from '../auth/SignInButton'
import { formatNumber } from '../rankings/data'
import { formatKm } from '../routes/geometry'
import { AccountTable, Empty, PageHeader, ReasonAction } from './common'
import { plural, releaseNote, voteMeta } from './format'
import { AdminIcon, Chip, type IconName, type Tone } from './icons'
import { AdminCountsContext, AdminSessionContext, useAdminError, when } from './session'

type Gate = 'checking' | 'ok' | 'sign-in' | 'forbidden' | 'error'

const NAV: { to: string; label: string; short: string; icon: IconName; count?: 'clusters' | 'held' }[] = [
  { to: '/admin', label: 'Review queue', short: 'Queue', icon: 'queue', count: 'clusters' },
  { to: '/admin/held', label: 'Held accounts', short: 'Held', icon: 'held', count: 'held' },
  { to: '/admin/accounts', label: 'Accounts', short: 'Accounts', icon: 'accounts' },
  { to: '/admin/buses', label: 'Bus routes', short: 'Bus routes', icon: 'bus' },
  { to: '/admin/audit', label: 'Audit log', short: 'Audit log', icon: 'audit' },
]

/**
 * /admin — the admin pages (Figma "RouteRank — Admin", A01–A10), linked from nowhere public: a sidebar on desktop,
 * tabs on phones. Entering needs a Google sign-in from the last 15 minutes; access then lasts while used and ends
 * after 30 minutes idle (the API checks; this shows the sign-in when it asks).
 */
export function AdminLayout() {
  const [gate, setGate] = useState<Gate>('checking')
  const [counts, setCounts] = useState<{ clusters?: number; held?: number }>({})
  const desktop = useMediaQuery(DESKTOP)
  const { pathname } = useLocation()
  // On phones, drawing a bus route is a sheet over the map rather than a page (A07)
  const drawingOnPhone = useMatch('/admin/buses/:id') !== null && !desktop
  const check = useCallback(() => {
    adminApi.session().then(
      () => setGate('ok'),
      (e: unknown) => setGate(e instanceof ApiError ? (e.status === 401 ? 'sign-in' : e.status === 403 ? 'forbidden' : 'error') : 'error'),
    )
  }, [])
  useEffect(check, [check])
  const expired = useCallback(() => setGate('sign-in'), [])
  const loadCounts = useCallback(() => {
    Promise.all([adminApi.queue(), adminApi.held()]).then(
      ([queue, held]) => setCounts({ clusters: queue.length, held: held.length }),
      () => undefined, // the pages show their own errors
    )
  }, [])
  // Again on every page and after every action, so the counts stay right
  useEffect(() => {
    if (gate === 'ok') loadCounts()
  }, [gate, pathname, loadCounts])

  if (gate === 'checking') return <main className="admin-gate" aria-busy="true" />
  if (gate !== 'ok') return <GateCard gate={gate} retry={() => { setGate('checking'); check() }} />

  const context = { expired, changed: loadCounts }
  if (drawingOnPhone) {
    return (
      <AdminSessionContext value={context}>
        <Outlet />
      </AdminSessionContext>
    )
  }
  return (
    <AdminSessionContext value={context}>
      <AdminCountsContext value={counts}>
        {desktop ? <Sidebar counts={counts} /> : <PhoneHeader counts={counts} />}
        <main className="admin-main">
          <Outlet />
        </main>
      </AdminCountsContext>
    </AdminSessionContext>
  )
}

/** A09 / A10: sign in again, not an admin, or the API couldn't be reached */
function GateCard({ gate, retry }: { gate: Exclude<Gate, 'ok' | 'checking'>; retry: () => void }) {
  return (
    <main className="admin-gate">
      <section className="admin-gate-card">
        <span className={`gate-icon ${gate === 'sign-in' ? 'teal' : gate === 'forbidden' ? 'red' : 'amber'}`}>
          <AdminIcon name={gate === 'error' ? 'refresh' : 'shield'} size={24} />
        </span>
        {gate === 'sign-in' && (
          <>
            <h1>Sign in again to use the admin pages</h1>
            <p>
              Admin pages need a Google sign-in from the last 15 minutes. You stay in while you use them and are signed
              out after 30 minutes idle.
            </p>
            <SignInButton
              onSignedIn={(me) => {
                signedIn(me)
                retry()
              }}
            />
          </>
        )}
        {gate === 'forbidden' && (
          <>
            <h1>Not available</h1>
            <p>This page is only for RouteRank's admins.</p>
            <Link className="a-btn plain" to="/">
              Back to the map
            </Link>
          </>
        )}
        {gate === 'error' && (
          <>
            <h1>Couldn't reach RouteRank</h1>
            <p>Check your connection and try again.</p>
            <button type="button" className="a-btn primary" onClick={retry}>
              Try again
            </button>
          </>
        )}
      </section>
    </main>
  )
}

function Count({ n }: { n?: number }) {
  // The space keeps the link's name readable ("Queue 2"); the layout is flex, so it takes no room
  return n ? (
    <>
      {' '}
      <span className="nav-count">{n}</span>
    </>
  ) : null
}

/** Admin / Sidebar: the desktop navigation */
function Sidebar({ counts }: { counts: { clusters?: number; held?: number } }) {
  const account = useAccount()
  // The admin got here signed in; load the account (once per visit) for its email
  useEffect(() => {
    loadAccount()
  }, [])
  return (
    <nav className="admin-sidebar" aria-label="Admin">
      <div className="admin-brand">
        <img src={logoMark} alt="" width={32} height={32} />
        <strong>RouteRank</strong>
        <Chip tone="teal">Admin</Chip>
      </div>
      {NAV.map((item) => (
        <NavLink key={item.to} to={item.to} end={item.to === '/admin'} className="nav-item">
          <AdminIcon name={item.icon} />
          <span>{item.label}</span>
          {item.count && <Count n={counts[item.count]} />}
        </NavLink>
      ))}
      <div className="admin-signed-in">
        <span>Signed in as admin</span>
        {account.kind === 'signed-in' && <strong>{account.me.email}</strong>}
        <span className="hint">Ends after 30 minutes idle</span>
      </div>
      <Link to="/" className="nav-item">
        <AdminIcon name="map" />
        <span>Back to the map</span>
      </Link>
    </nav>
  )
}

/** Admin / Phone header: title, the way back to the map, and tabs that scroll sideways */
function PhoneHeader({ counts }: { counts: { clusters?: number; held?: number } }) {
  const tabs = useRef<HTMLElement>(null)
  const { pathname } = useLocation()
  // Keep the current page's tab in view
  useEffect(() => {
    tabs.current?.querySelector('a.active')?.scrollIntoView?.({ block: 'nearest', inline: 'center' })
  }, [pathname])
  return (
    <header className="admin-phone-header">
      <div className="admin-brand">
        <img src={logoMark} alt="" width={30} height={30} />
        <strong>Admin</strong>
        <Link to="/" className="round-button" aria-label="Back to the map">
          <AdminIcon name="map" />
        </Link>
      </div>
      <nav className="admin-tabs" aria-label="Admin" ref={tabs}>
        {NAV.map((item) => (
          <NavLink key={item.to} to={item.to} end={item.to === '/admin'}>
            {item.short}
            {item.count && <Count n={counts[item.count]} />}
          </NavLink>
        ))}
      </nav>
    </header>
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

/** Rankings are scored at :00 and :30 */
function nextScoring(now = new Date()) {
  const next = new Date(now)
  next.setMinutes(now.getMinutes() < 30 ? 30 : 60, 0, 0)
  return next.toLocaleTimeString('en-LK', { hour: 'numeric', minute: '2-digit' })
}

function RunScoring({ onRun }: { onRun: (run: ScoringRun) => void }) {
  return (
    <ReasonAction
      label="Run scoring now"
      kind="primary"
      title="Run scoring now?"
      description="The rankings, the heatmap and the bus routes are worked out and published now, instead of at the next :00 or :30."
      confirmLabel="Run scoring"
      run={(r) => adminApi.runScoring(r).then(onRun)}
    />
  )
}

function Ran({ run }: { run: ScoringRun }) {
  return (
    <p className="admin-notice" role="status">
      {run.published ? `Published: ${formatNumber(run.ranked)} stretches ranked.` : `Scoring ran, nothing new to publish.`}
      {run.note ? ` ${run.note}` : ''}
    </p>
  )
}

const REASONS: Record<string, { name: string; tone: Tone; icon: IconName }> = {
  burst: { name: 'New accounts within an hour, same roads', tone: 'amber', icon: 'held' },
  shared_device: { name: 'Accounts on one device', tone: 'red', icon: 'shield' },
  honeypot: { name: 'Filled in the hidden field (a bot)', tone: 'red', icon: 'shield' },
}

/** /admin — A01: open clusters of flagged accounts, the most suspicious first; release or remove a whole cluster */
export function QueuePage() {
  const { data, error, reload } = useAdminLoad(adminApi.queue)
  const { held } = use(AdminCountsContext)
  const desktop = useMediaQuery(DESKTOP)
  const [run, setRun] = useState<ScoringRun>()
  const waiting = data?.reduce((n, c) => n + c.accounts.length, 0) ?? 0
  return (
    <>
      <PageHeader title="Review queue" actions={desktop && <RunScoring onRun={setRun} />}>
        Accounts the anti-fraud checks flagged, grouped and most suspicious first. Their votes stay out of the rankings
        until you release them.
      </PageHeader>
      {desktop ? (
        <div className="admin-stats">
          <Stat label="Open clusters" value={data ? String(data.length) : '…'} sub={`${plural(waiting, 'account')} waiting`} />
          <Stat label="Held accounts" value={held === undefined ? '…' : String(held)} sub="Out of the rankings until released" />
          <Stat label="Rankings" value="Every 30 min" sub={`Next update at ${nextScoring()} · Run now after a review`} />
        </div>
      ) : (
        <section className="admin-card scoring-card">
          <strong>Rankings update every 30 min</strong>
          <span>Next update at {nextScoring()}. Run it now after a review or a new bus route.</span>
          <RunScoring onRun={setRun} />
        </section>
      )}
      {run && <Ran run={run} />}
      {error && (
        <p role="alert" className="admin-alert">
          {error}
        </p>
      )}
      {!desktop && data && data.length > 0 && (
        <p className="admin-count">
          {plural(data.length, 'cluster')} · {plural(waiting, 'account')} waiting
        </p>
      )}
      {data?.length === 0 && <Empty title="Nothing to review.">New flags show here as soon as the checks raise them.</Empty>}
      {data?.map((c) => <ClusterCard key={c.key} cluster={c} onDone={reload} />)}
    </>
  )
}

function Stat({ label, value, sub }: { label: string; value: string; sub: string }) {
  return (
    <section className="admin-card stat">
      <span className="label">{label}</span>
      <strong>{value}</strong>
      <span>{sub}</span>
    </section>
  )
}

const counting = (routes: RouteSummary[]) => routes.filter((r) => !r.removedAt).length

function ClusterCard({ cluster, onDone }: { cluster: Cluster; onDone: () => void }) {
  const reason = REASONS[cluster.reason] ?? { name: cluster.reason, tone: 'amber', icon: 'held' }
  const accounts = plural(cluster.accounts.length, 'account')
  const votes = cluster.accounts.reduce((n, a) => n + counting(a.routes), 0)
  return (
    <article className="admin-card cluster">
      <header className="cluster-head">
        <span className={`reason-icon ${reason.tone}`}>
          <AdminIcon name={reason.icon} />
        </span>
        <div className="cluster-title">
          <h2>{reason.name}</h2>
          <p>
            {accounts} · score {cluster.score} · flagged {when(cluster.flaggedAt)} <Chip>{cluster.key}</Chip>
          </p>
        </div>
        <div className="admin-actions">
          <ReasonAction
            label="Release all"
            title={`Release ${accounts}?`}
            description={`Everyone in “${reason.name}”. Their votes join the rankings at the next scoring run, once each account's votes start counting.`}
            confirmLabel={`Release ${accounts}`}
            run={(r) => adminApi.releaseCluster(cluster.key, r)}
            onDone={onDone}
          />
          <ReasonAction
            label="Remove all their votes"
            kind="danger"
            title="Remove all their votes?"
            description={
              votes === 0
                ? `${accounts} in “${reason.name}”. They have no votes counting; the accounts stay held.`
                : `${accounts} in “${reason.name}”. Their ${plural(votes, 'vote')} leave the rankings at the next scoring run. You can restore single votes from each account’s page.`
            }
            confirmLabel={votes === 0 ? 'Remove all their votes' : `Remove ${plural(votes, 'vote')}`}
            run={(r) => adminApi.removeCluster(cluster.key, r)}
            onDone={onDone}
          />
        </div>
      </header>
      <AccountTable accounts={cluster.accounts} meta={(a) => `#${a.id} · made ${when(a.createdAt)}`} />
    </article>
  )
}

/** /admin/held — A03: every held account, including ones whose votes were removed */
export function HeldPage() {
  const { data, error, reload } = useAdminLoad(adminApi.held)
  return (
    <>
      <PageHeader title="Held accounts">
        Their votes stay out of the rankings, and so does anything they save, until you release them. Accounts whose
        votes you removed stay here too.
      </PageHeader>
      {error && (
        <p role="alert" className="admin-alert">
          {error}
        </p>
      )}
      {data?.length === 0 && <Empty title="No accounts are held." />}
      {data && data.length > 0 && (
        <section className="admin-card table-card">
          <AccountTable
            accounts={data}
            meta={(a) => `#${a.id} · held since ${when(a.heldAt)}${a.routes.length > 0 && counting(a.routes) === 0 ? ' · votes removed' : ''}`}
            action={(a) => (
              <ReasonAction
                label="Release"
                title={`Release ${a.email}?`}
                description={`${releaseNote(a.liveAt)} Anything they save counts as usual.`}
                run={(r) => adminApi.releaseAccount(a.id, r)}
                onDone={reload}
              />
            )}
          />
        </section>
      )}
    </>
  )
}

/** /admin/accounts — A04: find an account by email or ID */
export function AccountsPage() {
  const [q, setQ] = useState('')
  const [searched, setSearched] = useState('')
  const [results, setResults] = useState<AccountRow[]>()
  const [error, setError] = useState<string | null>(null)
  const describe = useAdminError()
  return (
    <>
      <PageHeader title="Accounts">Find an account by email or account ID to see its votes, release it, or ban it.</PageHeader>
      <form
        className="admin-search"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          adminApi.findAccounts(q).then((rows) => {
            setResults(rows)
            setSearched(q)
          }, (err: unknown) => setError(describe(err)))
        }}
      >
        <label className="search-field">
          <AdminIcon name="search" />
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Email or account ID" aria-label="Email or account ID" />
        </label>
        <button type="submit" className="a-btn primary">
          Find
        </button>
      </form>
      {error && (
        <p role="alert" className="admin-alert">
          {error}
        </p>
      )}
      {results?.length === 0 && <Empty title="No accounts found.">Try part of the email, or the account's number.</Empty>}
      {results && results.length > 0 && (
        <section className="admin-card table-card">
          <p className="table-caption">
            {plural(results.length, 'account')} {results.length === 1 ? 'matches' : 'match'} “{searched}”
          </p>
          <ul className="result-list">
            {results.map((a) => (
              <li key={a.id}>
                <Link to={`/admin/accounts/${a.id}`}>
                  <span className="who">
                    <strong>{a.email}</strong>
                    <span>
                      #{a.id} · made {when(a.createdAt)}
                    </span>
                  </span>
                  <span className="account-chips">
                    <AccountTags account={a} />
                  </span>
                  <span className="chevron" aria-hidden="true">
                    ›
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  )
}

function AccountTags({ account: a, role }: { account: AccountRow; role?: boolean }) {
  return (
    <>
      {role && <Chip>#{a.id}</Chip>}
      {(role || a.role === 'admin') && <Chip tone={a.role === 'admin' ? 'blue' : 'neutral'}>{a.role === 'admin' ? 'Admin' : 'Voter'}</Chip>}
      {a.heldAt && <Chip tone="amber">Held</Chip>}
      {a.bannedAt && <Chip tone="red">Banned</Chip>}
    </>
  )
}

/** Whether a vote counts now, and if not, why */
function voteStatus(r: RouteSummary, a: AccountRow): [Tone, string] {
  if (r.removedAt) return ['red', 'Removed']
  if (a.bannedAt) return ['red', 'Banned']
  if (a.heldAt) return ['amber', 'On hold']
  if (new Date(a.liveAt) > new Date()) return ['amber', `Counts from ${when(a.liveAt)}`]
  return ['green', 'Counting']
}

/** /admin/accounts/:id — A05: an account and its votes: release, ban or unban, remove or restore votes */
export function AccountPage() {
  const { id } = useParams()
  const load = useCallback(() => adminApi.account(Number(id)), [id])
  const { data, error, reload } = useAdminLoad<AccountDetail>(load)
  const back = (
    <Link className="back-link" to="/admin/accounts">
      ‹ Accounts
    </Link>
  )
  if (error) {
    return (
      <>
        {back}
        <p role="alert" className="admin-alert">
          {error}
        </p>
      </>
    )
  }
  if (!data) return <p aria-busy="true">Loading…</p>
  const a = data.account
  return (
    <>
      {back}
      <section className="admin-card account-summary">
        <div className="account-top">
          <div>
            <h1>{a.email}</h1>
            <div className="account-chips">
              <AccountTags account={a} role />
            </div>
          </div>
          <div className="admin-actions">
            {a.heldAt && (
              <ReasonAction
                label="Release"
                title={`Release ${a.email}?`}
                description={releaseNote(a.liveAt)}
                run={(r) => adminApi.releaseAccount(a.id, r)}
                onDone={reload}
              />
            )}
            {a.role !== 'admin' &&
              (a.bannedAt ? (
                <ReasonAction label="Unban" title={`Unban ${a.email}?`} description="They can sign in and vote again." run={(r) => adminApi.unban(a.id, r)} onDone={reload} />
              ) : (
                <ReasonAction
                  label="Ban"
                  kind="danger"
                  title={`Ban ${a.email}?`}
                  description="They can't sign in or vote, and their votes leave the rankings at the next scoring run."
                  run={(r) => adminApi.ban(a.id, r)}
                  onDone={reload}
                />
              ))}
          </div>
        </div>
        <dl className="account-facts">
          <div>
            <dt>Made</dt>
            <dd>{when(a.createdAt)}</dd>
          </div>
          <div>
            <dt>Votes count from</dt>
            <dd>{when(a.liveAt)}</dd>
          </div>
          <div>
            <dt>Trust score</dt>
            <dd>{a.trustScore}</dd>
          </div>
          {a.heldAt && (
            <div>
              <dt>Held since</dt>
              <dd>{when(a.heldAt)}</dd>
            </div>
          )}
          {a.bannedAt && (
            <div>
              <dt>Banned</dt>
              <dd>{when(a.bannedAt)}</dd>
            </div>
          )}
        </dl>
      </section>
      <section className="admin-card table-card">
        <h2 className="table-title">Votes</h2>
        {data.routes.length === 0 && <Empty title="No votes." />}
        <ul className="vote-list">
          {data.routes.map((r) => {
            const [tone, status] = voteStatus(r, a)
            return (
              <li key={r.id} className={r.removedAt ? 'removed' : undefined}>
                <span className="slot">#{r.slot}</span>
                <span className="vote-text">
                  <strong>{r.name}</strong>
                  <span>
                    {voteMeta(r)} · {r.removedAt ? `removed ${when(r.removedAt)}` : `saved ${when(r.updatedAt)}`}
                  </span>
                </span>
                <Chip tone={tone}>{status}</Chip>
                {r.removedAt ? (
                  <ReasonAction label="Restore" kind="plain" title={`Restore “${r.name}”?`} description="It counts again from the next scoring run, if the account's votes count." run={(reason) => adminApi.restoreRoute(r.id, reason)} onDone={reload} />
                ) : (
                  <ReasonAction label="Remove" kind="danger" title={`Remove “${r.name}”?`} description="It leaves the rankings at the next scoring run. You can restore it later." run={(reason) => adminApi.removeRoute(r.id, reason)} onDone={reload} />
                )}
              </li>
            )
          })}
        </ul>
      </section>
    </>
  )
}

const ACTIONS: Record<string, [string, Tone]> = {
  'scoring.run': ['Ran scoring', 'teal'],
  'bus_route.add': ['Added bus route', 'blue'],
  'bus_route.redraw': ['Redrew bus route', 'blue'],
  'bus_route.retire': ['Retired bus route', 'red'],
  'route.remove': ['Removed a vote', 'red'],
  'route.restore': ['Restored a vote', 'green'],
  'account.release': ['Released', 'green'],
  'account.remove_votes': ['Removed votes', 'red'],
  'account.ban': ['Banned', 'red'],
  'account.unban': ['Unbanned', 'green'],
  'account.role': ['Role changed', 'amber'],
}

/** "role: voter → admin" from the before and after JSON; the raw text if it isn't JSON */
function change(before?: string | null, after?: string | null): string | null {
  if (!before && !after) return null
  const parse = (s?: string | null): Record<string, unknown> | null => {
    if (!s) return {}
    try {
      const v = JSON.parse(s)
      return v && typeof v === 'object' && !Array.isArray(v) ? v : null
    } catch {
      return null
    }
  }
  const b = parse(before)
  const a = parse(after)
  if (!b || !a) return `${before ?? '–'} → ${after ?? '–'}`
  // Lengths are stored in metres ("lengthOutM")
  const show = (k: string, v: unknown) =>
    v === undefined || v === null ? '–' : typeof v === 'number' && k.endsWith('M') ? formatKm(v) : typeof v === 'object' ? JSON.stringify(v) : String(v)
  const keys = [...new Set([...Object.keys(b), ...Object.keys(a)])].filter((k) => JSON.stringify(b[k]) !== JSON.stringify(a[k]))
  // Something new (or gone) is just its values; a change shows both sides
  if (!before) return keys.map((k) => `${k}: ${show(k, a[k])}`).join(' · ')
  if (!after) return keys.map((k) => `${k}: ${show(k, b[k])}`).join(' · ')
  return keys.map((k) => `${k}: ${show(k, b[k])} → ${show(k, a[k])}`).join(' · ')
}

/** /admin/audit — A08: who did what, when and why, newest first */
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
    <>
      <PageHeader title="Audit log">Who did what, when and why, newest first. Entries can't be changed or deleted.</PageHeader>
      {error && (
        <p role="alert" className="admin-alert">
          {error}
        </p>
      )}
      <section className="admin-card table-card">
        <div className="audit-cols" aria-hidden="true">
          <span>When</span>
          <span>Who</span>
          <span>What</span>
          <span>Reason</span>
        </div>
        <ol className="audit-list">
          {entries.map((e) => {
            const [label, tone] = ACTIONS[e.action] ?? [e.action, 'neutral']
            const diff = change(e.before, e.after)
            return (
              <li key={e.id}>
                <span className="audit-when">{when(e.at)}</span>
                <span className={`audit-who${e.actorEmail ? '' : ' database'}`}>{e.actorEmail ?? 'database'}</span>
                <span className="audit-what">
                  <span>
                    <Chip tone={tone}>{label}</Chip>{' '}
                    {/* "scoring" only repeats "Ran scoring" */}
                    {e.target !== e.action.split('.')[0] && <span className="target">{e.target}</span>}
                  </span>
                  {diff && <span className="diff">{diff}</span>}
                </span>
                <span className="audit-reason">{e.reason}</span>
              </li>
            )
          })}
        </ol>
      </section>
      {more && entries.length > 0 && (
        <button type="button" className="a-btn plain" onClick={() => loadMore(entries.at(-1)!.id)}>
          Older entries
        </button>
      )}
    </>
  )
}
