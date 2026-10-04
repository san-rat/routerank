import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BusCheck, Preview } from '../api/client'
import { type Manifest, resetRankings } from '../rankings/data'
import { LeaderRow } from '../rankings/MapRankings'
import { BusCheckSheet } from '../routes/BusCheck'
import { getDraft } from '../routes/draft'
import { BusChip, BusPage } from './Buses'

const manifest: Manifest = {
  version: 1,
  generatedAt: '2026-10-05T04:00:00Z',
  pageSize: 20,
  bands: [10, 20, 40, 80],
  overall: { ranked: 0, pages: [] },
  provinces: {},
  slugs: 'slugs-a.json',
  buses: 'buses-b.json',
}

const bus99 = {
  number: '99',
  name: 'Pettah → Makumbura',
  startName: 'Pettah',
  endName: 'Makumbura',
  start: [79.85, 6.935],
  end: [79.93, 6.83],
  towns: ['Borella', 'Nugegoda', 'Maharagama'],
  lengthOutM: 21_400,
  lengthBackM: 21_100,
  out: [[79.85, 6.935], [79.93, 6.83]],
  back: [[79.93, 6.83], [79.85, 6.935]],
  bbox: [79.85, 6.83, 79.93, 6.935],
  wanted: { slug: 'makumbura-horana', name: 'Makumbura → Horana', province: 'western', points: 716, rankProvince: 6 },
}

beforeEach(() => {
  vi.stubEnv('VITE_DATA_URL', 'https://data.example')
  resetRankings()
  const files: Record<string, unknown> = { 'manifest.json': manifest, 'buses-b.json': { routes: [bus99] } }
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const key = url.replace('https://data.example/', '')
    return key in files ? new Response(JSON.stringify(files[key])) : new Response(null, { status: 404 })
  }))
})

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

function at(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/bus/:number" element={<BusPage />} />
        <Route path="*" element={<p>Elsewhere</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('bus routes on the map', () => {
  it('shows a bus route with the towns it passes and its most-wanted extension (W05)', async () => {
    at('/bus/99')
    expect(await screen.findByRole('heading', { name: 'Pettah → Makumbura' })).toBeInTheDocument()
    expect(screen.getByText('Existing bus route · 21.4 km')).toBeInTheDocument()
    expect(screen.getByText('Borella · Nugegoda · Maharagama')).toBeInTheDocument()
    expect(screen.getByText('Most-wanted extension: → Horana')).toBeInTheDocument()
    expect(screen.getByText('Busiest stretch #6 in Western Province · 716 pts')).toBeInTheDocument()

    // Proposing an extension starts a route where the most-wanted one leaves the bus
    await userEvent.click(screen.getByRole('button', { name: /Propose an extension/ }))
    expect(getDraft().start).toEqual({ lon: 79.93, lat: 6.83 })
    expect(getDraft().startLabel).toBe('Makumbura')
  })

  it('says when a bus route is not on RouteRank', async () => {
    at('/bus/400')
    expect(await screen.findByRole('heading', { name: 'No bus route 400 on RouteRank' })).toBeInTheDocument()
  })

  it('turns the red layer on and off', async () => {
    render(<BusChip />)
    const chip = await screen.findByRole('button', { name: /Bus routes/ })
    expect(chip).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(chip)
    expect(chip).toHaveAttribute('aria-pressed', 'false')
    await userEvent.click(chip)
  })

  it('tags a stretch mostly voted for as an extension', () => {
    render(
      <MemoryRouter>
        <LeaderRow
          entry={{ rank: 6, slug: 'makumbura-horana', name: 'Makumbura → Horana', province: 'western', points: 716,
            people: 300, lengthM: 15_400, bbox: [0, 0, 1, 1], extendsBus: '99' }}
          top={1000}
          manifest={manifest}
        />
      </MemoryRouter>,
    )
    expect(screen.getByText('Extends 99')).toBeInTheDocument()
  })
})

const preview = (lengthM: number, problems: Preview['problems'] = []): Preview => ({
  points: [], out: [], back: [], backLeaves: [], backVia: [], lengthOutM: lengthM, lengthBackM: lengthM,
  name: 'Pettah → Horana', problems,
})

const check = (total: number, problems: BusCheck['problems'] = []): BusCheck => ({
  busRouteId: 4, number: '99', busName: 'Pettah → Makumbura', busLengthM: 21_400, newLengthM: total - 21_400,
  totalM: total, limitM: 40_000, name: 'Pettah → Horana', newEnd: 'Horana', problems,
})

describe('the Bus check', () => {
  it('suggests extending the bus, which counts only the new part (W18)', async () => {
    const onChoose = vi.fn()
    render(<BusCheckSheet preview={preview(36_800)} bus={check(36_800)} onChoose={onChoose} />)
    expect(screen.getByRole('heading', { name: 'Bus 99 already runs part of this' })).toBeInTheDocument()
    const extend = screen.getByRole('radio', { name: /Extend Route 99 to Horana/ })
    expect(extend).toHaveAttribute('aria-checked', 'true')
    expect(within(extend).getByText('Suggested')).toBeInTheDocument()
    expect(within(extend).getByText(/= 36.8 km of the 40 km max. Only the new part earns points./)).toBeInTheDocument()
    expect(within(extend).getByText('36.8 / 40 km')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))
    expect(onChoose).toHaveBeenCalledWith(4)

    await userEvent.click(screen.getByRole('radio', { name: /Keep it as a new route/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))
    expect(onChoose).toHaveBeenLastCalledWith(undefined)
  })

  it('blocks extending when bus + new part is over 40 km (W18b)', () => {
    const over = check(46_200, [{ code: 'EXTENSION_TOO_LONG', lengthM: 46_200, limitM: 40_000 }])
    render(<BusCheckSheet preview={preview(38_200)} bus={over} onChoose={() => undefined} />)
    const extend = screen.getByRole('radio', { name: /Extend Route 99 to Horana/ })
    expect(extend).toBeDisabled()
    expect(within(extend).getByText('Over 40 km')).toBeInTheDocument()
    expect(within(extend).getByText(/= 46.2 km, over the 40 km max./)).toBeInTheDocument()
    const keep = screen.getByRole('radio', { name: /Keep it as a new route/ })
    expect(keep).toHaveAttribute('aria-checked', 'true')
    expect(within(keep).getByText('Your route alone is 38.2 km, so it can still be saved as a new route.')).toBeInTheDocument()
  })
})
