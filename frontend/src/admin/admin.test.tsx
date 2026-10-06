import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AccountDetail, AuditEntry, Cluster } from '../api/client'
import { AccountPage, AdminLayout, AuditPage, QueuePage } from './AdminPages'

const cluster: Cluster = {
  key: 'burst:42',
  reason: 'burst',
  flaggedAt: '2026-10-05T01:00:00Z',
  score: 1,
  accounts: [
    {
      id: 42,
      email: 'new-voter@example.com',
      createdAt: '2026-10-04T23:00:00Z',
      liveAt: '2026-10-05T23:00:00Z',
      heldAt: '2026-10-05T01:00:00Z',
      bannedAt: null as unknown as string,
      trustScore: 1,
      reasons: ['burst'],
      routes: [
        {
          id: 7, userId: 42, slot: 1, name: 'Thummulla → Havelock', kind: 'new', busNumber: null as unknown as string,
          lengthM: 1_400, createdAt: '2026-10-04T23:01:00Z', updatedAt: '2026-10-04T23:01:00Z', removedAt: null as unknown as string,
        },
      ],
    },
  ],
}

function respond(handler: (url: string, init?: RequestInit) => { status?: number; body?: unknown }) {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const { status = 200, body } = handler(url, init)
    return body === undefined ? new Response(null, { status: status === 200 ? 204 : status }) : new Response(JSON.stringify(body), { status })
  })
  vi.stubGlobal('fetch', fetch)
  return fetch
}

function admin(path = '/admin') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/admin" element={<AdminLayout />}>
          <Route index element={<QueuePage />} />
          <Route path="accounts/:id" element={<AccountPage />} />
          <Route path="audit" element={<AuditPage />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('the admin pages', () => {
  it('ask for a fresh Google sign-in (A09)', async () => {
    respond(() => ({ status: 401, body: { code: 'fresh_sign_in' } }))
    admin()
    expect(await screen.findByRole('heading', { name: 'Sign in again to use the admin pages' })).toBeInTheDocument()
  })

  it('are closed to voters (A10)', async () => {
    respond(() => ({ status: 403 }))
    admin()
    expect(await screen.findByRole('heading', { name: 'Not available' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to the map' })).toHaveAttribute('href', '/')
  })

  it('release a whole cluster with one written reason (A01, A02)', async () => {
    let queue = [cluster]
    const fetch = respond((url, init) => {
      if (url === '/api/admin/session') return { body: { adminId: 1 } }
      if (url === '/api/admin/queue') return { body: queue }
      if (url === '/api/admin/held') return { body: queue.flatMap((c) => c.accounts) }
      if (url === '/api/admin/clusters/release' && init?.method === 'POST') {
        queue = []
        return { body: { users: [42], removedRoutes: [] } }
      }
      return { status: 404 }
    })
    admin()
    expect(await screen.findByRole('heading', { name: 'New accounts within an hour, same roads' })).toBeInTheDocument()
    expect(screen.getByText(/Thummulla → Havelock/)).toBeInTheDocument()
    // The tabs show what's waiting
    const tabs = screen.getByRole('navigation', { name: 'Admin' })
    expect(await within(tabs).findByRole('link', { name: 'Queue 1' })).toHaveClass('active')

    await userEvent.click(screen.getByRole('button', { name: 'Release all' }))
    const dialog = screen.getByRole('dialog', { name: 'Release 1 account?' })
    const confirm = within(dialog).getByRole('button', { name: 'Release 1 account' })
    expect(confirm).toBeDisabled() // no reason, no action
    await userEvent.type(within(dialog).getByLabelText(/Reason/), 'Shared in a school group')
    await userEvent.click(confirm)

    const call = fetch.mock.calls.find(([url]) => url === '/api/admin/clusters/release')!
    expect(JSON.parse(String(call[1]!.body))).toEqual({ cluster: 'burst:42', reason: 'Shared in a school group' })
    expect(await screen.findByText('Nothing to review.')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('says why a held account’s votes don’t count, and asks before removing one (A05)', async () => {
    const detail: AccountDetail = {
      account: {
        id: 42, email: 'new-voter@example.com', role: 'voter', createdAt: '2026-10-04T23:00:00Z',
        liveAt: '2026-10-05T23:00:00Z', heldAt: '2026-10-05T01:00:00Z', bannedAt: null as unknown as string, trustScore: 1,
      },
      routes: [cluster.accounts[0].routes[0], { ...cluster.accounts[0].routes[0], id: 8, slot: 2, name: 'Borella → Town Hall', removedAt: '2026-10-05T02:00:00Z' }],
    }
    respond((url) => {
      if (url === '/api/admin/session') return { body: { adminId: 1 } }
      if (url === '/api/admin/accounts/42') return { body: detail }
      if (url === '/api/admin/queue' || url === '/api/admin/held') return { body: [] }
      return { status: 404 }
    })
    admin('/admin/accounts/42')
    expect(await screen.findByRole('heading', { name: 'new-voter@example.com' })).toBeInTheDocument()
    const votes = screen.getAllByRole('listitem').filter((li) => li.closest('.vote-list'))
    expect(within(votes[0]).getByText('On hold')).toBeInTheDocument()
    expect(within(votes[1]).getByText('Removed')).toBeInTheDocument()
    expect(within(votes[1]).getByRole('button', { name: 'Restore' })).toBeInTheDocument()

    await userEvent.click(within(votes[0]).getByRole('button', { name: 'Remove' }))
    const dialog = screen.getByRole('dialog', { name: 'Remove “Thummulla → Havelock”?' })
    expect(within(dialog).getByText(/You can restore it later/)).toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('show the audit log in plain words (A08)', async () => {
    const entries: AuditEntry[] = [
      { id: 2, actorId: 1, actorEmail: 'admin@example.com', action: 'account.ban', target: 'account:27', before: '{"banned":false}', after: '{"banned":true}', reason: 'A bot', at: '2026-10-05T10:00:00Z' },
      { id: 1, actorId: null as unknown as number, actorEmail: null as unknown as string, action: 'account.role', target: 'account:3', before: '{"role":"voter"}', after: '{"role":"admin"}', reason: 'Changed in the database by routerank', at: '2026-10-04T10:00:00Z' },
    ]
    respond((url) => {
      if (url === '/api/admin/session') return { body: { adminId: 1 } }
      if (url === '/api/admin/audit') return { body: entries }
      if (url === '/api/admin/queue' || url === '/api/admin/held') return { body: [] }
      return { status: 404 }
    })
    admin('/admin/audit')
    expect(await screen.findByText('Banned')).toBeInTheDocument()
    expect(screen.getByText('banned: false → true')).toBeInTheDocument()
    expect(screen.getByText('Role changed')).toBeInTheDocument()
    expect(screen.getByText('role: voter → admin')).toBeInTheDocument()
    expect(screen.getByText('database')).toBeInTheDocument()
    // Fewer than a page: no "Older entries"
    expect(screen.queryByRole('button', { name: 'Older entries' })).not.toBeInTheDocument()
  })
})
