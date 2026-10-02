import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { routes } from './routes'

// MapLibre needs WebGL, which jsdom lacks; the map itself is checked in a real browser
vi.mock('../map/MapView', () => ({
  default: ({ province }: { province?: { name: string } }) => <div data-testid="map">{province?.name}</div>,
}))

function open(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(<RouterProvider router={router} />)
  return router
}

describe('routes', () => {
  it('/map/western opens the map on Western Province', async () => {
    open('/map/western')
    expect(await screen.findByTestId('map')).toHaveTextContent('Western')
    expect(screen.getByRole('button', { name: /Western Province/ })).toBeInTheDocument()
  })

  it('an unknown province goes to Western', async () => {
    const router = open('/map/atlantis')
    await screen.findByTestId('map')
    expect(router.state.location.pathname).toBe('/map/western')
  })

  it('the province picker moves to the chosen province', async () => {
    const router = open('/map/western')
    await userEvent.click(await screen.findByRole('button', { name: /Western Province/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Central Province' }))
    expect(router.state.location.pathname).toBe('/map/central')
    expect(await screen.findByTestId('map')).toHaveTextContent('Central')
  })

  it('pages without a map do not load it on mobile', async () => {
    open('/leaderboard')
    expect(await screen.findByRole('heading', { name: 'Leaderboard' })).toBeInTheDocument()
    expect(screen.queryByTestId('map')).not.toBeInTheDocument()
  })
})
