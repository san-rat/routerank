import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MyRoutes, Preview } from '../api/client'
import { resetAccount } from '../auth/account'
import { blockingProblems, draftActions, getDraft } from './draft'
import { resetMyRoutes } from './myRoutes'
import { RankPage } from './RankPage'

vi.mock('../fraud/botCheck', () => ({
  turnstileToken: vi.fn(async () => 'turnstile-token'),
  deviceSignal: vi.fn(async () => 'visitor-id'),
}))

const me = { id: 1, email: 'voter@example.com', createdAt: '2026-10-03T00:00:00Z', liveAt: '2026-10-04T00:00:00Z' }
const mine: MyRoutes = { routes: [], slots: [{ slot: 1 }, { slot: 2 }, { slot: 3 }], countsFrom: '2026-10-04T00:00:00Z' }

// Kollupitiya → Bambalapitiya overlaps the user's route on the bus's road, so only extending works
const preview: Preview = {
  points: [{ lat: 6.9147, lon: 79.8488 }, { lat: 6.889, lon: 79.8553 }],
  out: [[79.8488, 6.9147], [79.8553, 6.889]],
  back: [[79.8553, 6.889], [79.8488, 6.9147]],
  backLeaves: [],
  backVia: [],
  lengthOutM: 3_100,
  lengthBackM: 3_100,
  name: 'Kollupitiya → Bambalapitiya',
  problems: [{ code: 'OVERLAP', routeId: 9, slot: 2, routeName: 'Fort → Kollupitiya' }],
  bus: {
    busRouteId: 4, number: '101', busName: 'Fort → Kollupitiya', busLengthM: 2_900, newLengthM: 3_100, totalM: 6_000,
    limitM: 40_000, name: 'Fort → Bambalapitiya', newEnd: 'Bambalapitiya', problems: [],
  },
}

let fetch: ReturnType<typeof vi.fn>

beforeEach(async () => {
  localStorage.setItem('routerank.signedIn', '1')
  fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const body = url === '/api/me' ? me : url === '/api/routes/preview' ? preview
      : url === '/api/routes' && init?.method === 'POST' ? { id: 12, slot: 1, name: 'Fort → Bambalapitiya', start: preview.points[0],
        end: preview.points[1], waypoints: [], out: preview.out, back: preview.back, lengthOutM: 3_100, lengthBackM: 3_100,
        createdAt: '2026-10-05T00:00:00Z', updatedAt: '2026-10-05T00:00:00Z', extendsBus: { id: 4, number: '101' } }
      : mine
    return new Response(JSON.stringify(body), { status: url === '/api/routes' && init?.method === 'POST' ? 201 : 200 })
  })
  vi.stubGlobal('fetch', fetch)
  draftActions.reset()
  draftActions.setStart(preview.points[0], 'Kollupitiya')
  draftActions.setEnd(preview.points[1], 'Bambalapitiya')
  await waitFor(() => expect(getDraft().preview).toBeDefined())
})

afterEach(() => {
  vi.unstubAllGlobals()
  resetAccount()
  resetMyRoutes()
  localStorage.clear()
})

describe('saving after the Bus check', () => {
  it('blocks a new route that overlaps, but not the extension', () => {
    expect(blockingProblems(getDraft())).toHaveLength(1)
    draftActions.setExtend(4)
    expect(blockingProblems(getDraft())).toEqual([])
    draftActions.setExtend(99) // not the bus the preview found
    expect(blockingProblems(getDraft())[0].code).toBe('NOT_AN_EXTENSION')
  })

  it('saves the extension with the bot check and the device signal, never geometry', async () => {
    draftActions.setExtend(4)
    render(
      <MemoryRouter>
        <RankPage />
      </MemoryRouter>,
    )
    expect(await screen.findByText('Extends bus 101 · 3.1 km new earns points')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('radio', { name: /#1/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Save my vote' }))
    expect(await screen.findByRole('heading', { name: 'Your vote is in!' })).toBeInTheDocument()

    const save = fetch.mock.calls.find(([url, init]) => url === '/api/routes' && init?.method === 'POST')!
    const body = JSON.parse(String(save[1].body))
    expect(body).toEqual({
      start: preview.points[0],
      end: preview.points[1],
      waypoints: [],
      slot: 1,
      extend: 4,
      turnstile: 'turnstile-token',
      device: 'visitor-id',
    })
  })

  it('changing the points asks the Bus check again', () => {
    draftActions.setExtend(4)
    draftActions.setEnd({ lat: 6.88, lon: 79.86 }, 'Wellawatte')
    expect(getDraft().extend).toBeUndefined()
  })
})
