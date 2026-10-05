import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Cluster } from '../api/client'
import { AdminLayout, QueuePage } from './AdminPages'

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

function admin() {
  render(
    <MemoryRouter initialEntries={['/admin']}>
      <Routes>
        <Route path="/admin" element={<AdminLayout />}>
          <Route index element={<QueuePage />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('the admin pages', () => {
  it('ask for a fresh Google sign-in', async () => {
    respond(() => ({ status: 401, body: { code: 'fresh_sign_in' } }))
    admin()
    expect(await screen.findByText(/Sign in with Google again to use the admin pages/)).toBeInTheDocument()
  })

  it('are closed to voters', async () => {
    respond(() => ({ status: 403 }))
    admin()
    expect(await screen.findByRole('heading', { name: 'Not available' })).toBeInTheDocument()
  })

  it('release a whole cluster with one written reason', async () => {
    let queue = [cluster]
    const fetch = respond((url, init) => {
      if (url === '/api/admin/session') return { body: { adminId: 1 } }
      if (url === '/api/admin/queue') return { body: queue }
      if (url === '/api/admin/clusters/release' && init?.method === 'POST') {
        queue = []
        return { body: { users: [42], removedRoutes: [] } }
      }
      return { status: 404 }
    })
    admin()
    expect(await screen.findByText('New accounts within an hour, same roads')).toBeInTheDocument()
    expect(screen.getByText(/Thummulla → Havelock/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Release all' }))
    const confirm = screen.getByRole('button', { name: 'Confirm: release all' })
    expect(confirm).toBeDisabled() // no reason, no action
    await userEvent.type(screen.getByLabelText(/reason \(goes in the audit log\)/), 'Shared in a school group')
    await userEvent.click(confirm)

    const call = fetch.mock.calls.find(([url]) => url === '/api/admin/clusters/release')!
    expect(JSON.parse(String(call[1]!.body))).toEqual({ cluster: 'burst:42', reason: 'Shared in a school group' })
    expect(await screen.findByText('Nothing to review.')).toBeInTheDocument()
  })
})
