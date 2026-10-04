import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { resetAccount } from './account'
import { AccountPage } from './AccountPage'

function respond(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const me = { id: 1, email: 'alice@example.com', createdAt: '2026-10-03T00:00:00Z', liveAt: '2026-10-04T00:00:00Z' }

afterEach(() => {
  vi.unstubAllGlobals()
  resetAccount()
  document.cookie = 'XSRF-TOKEN=; max-age=0'
})

describe('AccountPage', () => {
  it('asks a signed-out visitor to sign in', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => respond(401)))
    render(<AccountPage />)
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('shows the signed-in account and when its votes count', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => respond(200, me)))
    render(<AccountPage />)
    expect(await screen.findByText('alice@example.com')).toBeInTheDocument()
    expect(screen.getByText('Your votes count from')).toBeInTheDocument()
    expect(localStorage.getItem('routerank.signedIn')).toBe('1')
  })

  it('signs out with the CSRF token', async () => {
    document.cookie = 'XSRF-TOKEN=token-123'
    const fetch = vi.fn(async (_url: string, init?: RequestInit) =>
      init?.method === 'POST' ? respond(204) : respond(200, me),
    )
    vi.stubGlobal('fetch', fetch)
    render(<AccountPage />)
    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }))

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(localStorage.getItem('routerank.signedIn')).toBeNull()
    const [url, init] = fetch.mock.calls.find(([, init]) => init?.method === 'POST')!
    expect(url).toBe('/api/auth/logout')
    expect(new Headers(init!.headers).get('X-XSRF-TOKEN')).toBe('token-123')
  })
})
