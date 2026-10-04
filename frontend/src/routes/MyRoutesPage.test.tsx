import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { MyRoutes } from '../api/client'
import { resetAccount } from '../auth/account'
import { MyRoutesPage } from './MyRoutesPage'
import { resetMyRoutes } from './myRoutes'

const me = { id: 1, email: 'sanuk.ratnayake@example.com', createdAt: '2026-10-03T00:00:00Z', liveAt: '2999-01-01T00:00:00Z' }

const route = (id: number, slot: number, name: string) => ({
  id,
  slot,
  name,
  start: { lat: 6.93, lon: 79.85 },
  end: { lat: 6.9, lon: 79.87 },
  waypoints: [],
  out: [
    [79.85, 6.93],
    [79.87, 6.9],
  ],
  back: [
    [79.87, 6.9],
    [79.85, 6.93],
  ],
  lengthOutM: 14_200,
  lengthBackM: 14_600,
  createdAt: '2026-10-04T00:00:00Z',
  updatedAt: '2026-10-04T00:00:00Z',
})

const mine: MyRoutes = {
  routes: [route(7, 1, 'Pettah → Horana'), route(8, 2, 'Kadawatha → Nittambuwa')],
  slots: [{ slot: 1, lockedUntil: '2999-01-01T00:00:00Z' }, { slot: 2 }, { slot: 3 }],
  countsFrom: '2999-01-01T00:00:00Z',
}

function respond(handler: (url: string, init?: RequestInit) => unknown) {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const body = handler(url, init)
    return body === undefined ? new Response(null, { status: 204 }) : new Response(JSON.stringify(body), { status: 200 })
  })
  vi.stubGlobal('fetch', fetch)
  return fetch
}

function page() {
  render(
    <MemoryRouter>
      <MyRoutesPage />
    </MemoryRouter>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
  resetAccount()
  resetMyRoutes()
})

describe('My routes', () => {
  it('invites anonymous visitors to sign in without calling the API', async () => {
    const fetch = respond(() => ({}))
    page()
    expect(await screen.findByRole('heading', { name: 'Pick up to 3 routes' })).toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('lists the routes by slot with their points, status and locks', async () => {
    localStorage.setItem('routerank.signedIn', '1')
    respond((url) => (url === '/api/me' ? me : mine))
    page()
    const slots = await screen.findAllByRole('listitem')
    expect(within(slots[0]).getByText('Pettah → Horana')).toBeInTheDocument()
    expect(within(slots[0]).getByText('+3 pts')).toBeInTheDocument()
    expect(within(slots[0]).getByText('Counts in 24 h')).toBeInTheDocument()
    expect(within(slots[0]).getByText(/Locked/)).toBeInTheDocument()
    expect(within(slots[1]).getByText('Kadawatha → Nittambuwa')).toBeInTheDocument()
    expect(within(slots[2]).getByText('Add your #3 route')).toBeInTheDocument()
    expect(screen.getByText('2 of 3 slots used · tap ↑ ↓ to reorder')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Move Pettah → Horana up' })).toBeDisabled()
  })

  it('moves a route down by saving the new order', async () => {
    localStorage.setItem('routerank.signedIn', '1')
    const fetch = respond((url, init) => (url === '/api/me' ? me : init?.method === 'PUT' ? mine : mine))
    page()
    await userEvent.click(await screen.findByRole('button', { name: 'Move Kadawatha → Nittambuwa down' }))
    const put = fetch.mock.calls.find(([, init]) => init?.method === 'PUT')
    expect(put?.[0]).toBe('/api/routes/order')
    expect(JSON.parse(put?.[1]?.body as string)).toEqual({ routes: [{ routeId: 7, slot: 1 }, { routeId: 8, slot: 3 }] })
  })

  it('asks before removing a route', async () => {
    localStorage.setItem('routerank.signedIn', '1')
    const fetch = respond((url) => (url === '/api/me' ? me : url.startsWith('/api/routes/') ? undefined : mine))
    page()
    await userEvent.click(await screen.findByRole('button', { name: 'Actions for Kadawatha → Nittambuwa' }))
    await userEvent.click(screen.getByRole('button', { name: /Remove route/ }))
    const dialog = screen.getByRole('dialog', { name: 'Remove this route?' })
    expect(within(dialog).getByText(/2 points come off at the next update/)).toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove route' }))
    expect(fetch).toHaveBeenCalledWith('/api/routes/8', expect.objectContaining({ method: 'DELETE' }))
  })
})
