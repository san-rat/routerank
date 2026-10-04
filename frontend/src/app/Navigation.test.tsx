import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { resetAccount } from '../auth/account'
import { PanelHeader } from './Navigation'

const me = { id: 1, email: 'alice@example.com', createdAt: '2026-10-03T00:00:00Z', liveAt: '2026-10-04T00:00:00Z' }

function header() {
  render(
    <MemoryRouter>
      <PanelHeader />
    </MemoryRouter>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
  resetAccount()
})

describe('PanelHeader', () => {
  it('shows "Sign in" to anonymous visitors without calling the API', async () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    header()
    expect(await screen.findByRole('link', { name: 'Sign in' })).toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('shows the account once this browser has signed in', async () => {
    localStorage.setItem('routerank.signedIn', '1')
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(me), { status: 200 })))
    header()
    expect(await screen.findByRole('link', { name: 'Account: alice@example.com' })).toHaveAttribute('href', '/me')
  })

  it('falls back to "Sign in" when the session has expired', async () => {
    localStorage.setItem('routerank.signedIn', '1')
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 401 })))
    header()
    expect(await screen.findByRole('link', { name: 'Sign in' })).toBeInTheDocument()
    expect(localStorage.getItem('routerank.signedIn')).toBeNull()
  })
})
