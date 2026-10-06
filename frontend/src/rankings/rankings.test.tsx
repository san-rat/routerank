import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getDraft } from '../routes/draft'
import { band, type Details, type Manifest, type Page, resetRankings } from './data'
import { type Focus, useFocus } from './focus'
import { LeaderboardPage } from './LeaderboardPage'
import { NoVotes, StretchPage } from './MapRankings'

const manifest: Manifest = {
  version: 1,
  generatedAt: '2026-10-04T04:00:00Z',
  pageSize: 20,
  bands: [10, 20, 40, 80],
  overall: { ranked: 2, pages: ['leaderboard/overall-1-aaa.json'] },
  provinces: {
    western: {
      name: 'Western',
      stretches: 3,
      ranked: 2,
      people: 41,
      heat: 'heat/western-bbb.json',
      details: 'details/western-ccc.json',
      pages: ['leaderboard/western-1-ddd.json'],
    },
    northern: { name: 'Northern', stretches: 0, ranked: 0, people: 0, heat: null, details: null, pages: [] },
  },
  slugs: 'slugs-eee.json',
}

const entry = (rank: number, slug: string, name: string, points: number) => ({
  rank,
  slug,
  name,
  province: 'western',
  points,
  people: Math.round(points / 2.5),
  lengthM: 14_200,
  bbox: [79.9, 6.9, 80.0, 7.1] as [number, number, number, number],
})

const page: Page = {
  page: 1,
  entries: [entry(1, 'kadawatha-nittambuwa', 'Kadawatha → Nittambuwa', 1284), entry(2, 'homagama-godagama', 'Homagama → Godagama', 1106)],
}

const details: Details = {
  province: 'Western',
  stretches: {
    'kadawatha-nittambuwa': {
      name: 'Kadawatha → Nittambuwa',
      road: 'A1',
      province: 'Western',
      points: 1284,
      people: 552,
      votes: [300, 132, 120],
      lengthM: 14_200,
      rankOverall: 1,
      rankProvince: 1,
      ends: [[79.95, 7.0], [80.05, 7.08]],
      bbox: [79.95, 7.0, 80.05, 7.08],
    },
    // Avissawella Road: one road, split where its number changes (B435, then AB10)
    'grandpass-ambatale': {
      name: 'Grandpass → Ambatale', road: 'Avissawella Road', province: 'Western', points: 3, people: 1, votes: [1, 0, 0],
      lengthM: 8_244, rankOverall: null, rankProvince: null, ends: [[79.879, 6.944], [79.945, 6.937]],
      bbox: [79.879, 6.937, 79.945, 6.944], along: 'grandpass-ambatale',
    },
    'ambatale-kaduwela': {
      name: 'Ambatale → Kaduwela', road: 'Avissawella Road', province: 'Western', points: 3, people: 1, votes: [1, 0, 0],
      lengthM: 4_400, rankOverall: null, rankProvince: null, ends: [[79.945, 6.937], [79.984, 6.936]],
      bbox: [79.945, 6.936, 79.984, 6.937], along: 'grandpass-ambatale',
    },
  },
  roads: {
    'grandpass-ambatale': {
      name: 'Avissawella Road', lengthM: 12_644, stretches: ['grandpass-ambatale', 'ambatale-kaduwela'],
      bbox: [79.879, 6.936, 79.984, 6.944],
    },
  },
}

const files: Record<string, unknown> = {
  'manifest.json': manifest,
  'leaderboard/overall-1-aaa.json': page,
  'leaderboard/western-1-ddd.json': page,
  'details/western-ccc.json': details,
  'slugs-eee.json': {
    'kadawatha-nittambuwa': ['kadawatha-nittambuwa', 'western'],
    'old-name': ['kadawatha-nittambuwa', 'western'],
    'ambatale-kaduwela': ['ambatale-kaduwela', 'western'],
  },
}

let fetch: ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.stubEnv('VITE_DATA_URL', 'https://data.example')
  resetRankings()
  fetch = vi.fn(async (url: string) => {
    const key = url.replace('https://data.example/', '')
    return key in files ? new Response(JSON.stringify(files[key])) : new Response(null, { status: 404 })
  })
  vi.stubGlobal('fetch', fetch)
})

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

function at(path: string, element: React.ReactNode, pattern: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path={pattern} element={element} />
        <Route path="*" element={<p>Elsewhere</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('the rankings', () => {
  it('colours scores with the national bands', () => {
    expect(band(5, manifest.bands)).toBe(0)
    expect(band(25, manifest.bands)).toBe(2)
    expect(band(100, manifest.bands)).toBe(4)
    expect(band(7, [5])).toBe(4) // one threshold: the two ends of the scale
    expect(band(7, [])).toBe(2)
  })

  it('lists the overall leaderboard from the published files, never the API (W10)', async () => {
    at('/leaderboard', <LeaderboardPage />, '/leaderboard')
    const first = await screen.findByRole('link', { name: /Kadawatha → Nittambuwa/ })
    expect(within(first).getByText('1,284 pts')).toBeInTheDocument()
    expect(within(first).getByText('Western · 14.2 km')).toBeInTheDocument()
    expect(first).toHaveAttribute('href', '/s/kadawatha-nittambuwa')
    expect(screen.getAllByRole('link', { name: /pts/ })).toHaveLength(2)
    expect(screen.getByText(/Updated every 30 min · last at/)).toBeInTheDocument()
    expect(fetch.mock.calls.every(([url]) => String(url).startsWith('https://data.example/'))).toBe(true)
  })

  it('shows a province with its counts (W11)', async () => {
    at('/leaderboard/western', <LeaderboardPage />, '/leaderboard/:province')
    expect(await screen.findByText('3 stretches · 41 people voted')).toBeInTheDocument()
    expect(await screen.findAllByText('14.2 km')).toHaveLength(2) // no province on a province's own list
  })

  it('opens a stretch from an old link and starts a vote between its ends (W04)', async () => {
    at('/s/old-name', <StretchPage />, '/s/:stretch')
    expect(await screen.findByRole('heading', { name: 'Kadawatha → Nittambuwa' })).toBeInTheDocument()
    expect(screen.getByText('A1 · 14.2 km')).toBeInTheDocument()
    expect(screen.getByText('300 × #1 = 900')).toBeInTheDocument()
    expect(screen.getByText('Western Prov.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Vote for this stretch/ }))
    expect(getDraft().start).toEqual({ lon: 79.95, lat: 7.0 })
    expect(getDraft().end).toEqual({ lon: 80.05, lat: 7.08 })
  })

  it('shows a stretch as part of its whole road, and the map draws all of it', async () => {
    let focus: Focus | null = null
    function Probe() {
      focus = useFocus()
      return null
    }
    render(
      <MemoryRouter initialEntries={['/s/ambatale-kaduwela']}>
        <Routes>
          <Route path="/s/:stretch" element={<><StretchPage /><Probe /></>} />
        </Routes>
      </MemoryRouter>,
    )
    expect(await screen.findByText('Part of Avissawella Road · 12.6 km')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Grandpass → Ambatale · 3 pts' })).toHaveAttribute('href', '/s/grandpass-ambatale')
    expect(screen.getByText('Ambatale → Kaduwela · 3 pts')).toHaveAttribute('aria-current', 'true')
    // Each stretch still has its own points
    expect(screen.getByRole('heading', { name: 'Ambatale → Kaduwela' })).toBeInTheDocument()
    expect(focus).toEqual({
      slug: 'ambatale-kaduwela',
      province: 'western',
      bbox: [79.879, 6.936, 79.984, 6.944],
      road: ['grandpass-ambatale', 'ambatale-kaduwela'],
    })
  })

  it('shows no whole road for a stretch on its own', async () => {
    at('/s/kadawatha-nittambuwa', <StretchPage />, '/s/:stretch')
    expect(await screen.findByRole('heading', { name: 'Kadawatha → Nittambuwa' })).toBeInTheDocument()
    expect(screen.queryByText(/Part of/)).not.toBeInTheDocument()
  })

  it('shares a stretch with the share sheet, or copies its link', async () => {
    const share = vi.fn(async () => undefined)
    vi.stubGlobal('navigator', { ...navigator, share })
    at('/s/kadawatha-nittambuwa', <StretchPage />, '/s/:stretch')
    await userEvent.click(await screen.findByRole('button', { name: 'Share this stretch' }))
    expect(share).toHaveBeenCalledWith({ title: 'Kadawatha → Nittambuwa on RouteRank', url: `${location.origin}/s/kadawatha-nittambuwa` })
    expect(screen.queryByText('Link copied')).not.toBeInTheDocument()

    // No share sheet: copies, and says so only when the copy worked
    const writeText = vi.fn(async (): Promise<void> => Promise.reject(new Error('denied')))
    vi.stubGlobal('navigator', { ...navigator, share: undefined, clipboard: { writeText } })
    await userEvent.click(screen.getByRole('button', { name: 'Share this stretch' }))
    expect(writeText).toHaveBeenCalled()
    expect(screen.queryByText('Link copied')).not.toBeInTheDocument()
    writeText.mockImplementation(async () => undefined)
    await userEvent.click(screen.getByRole('button', { name: 'Share this stretch' }))
    expect(await screen.findByText('Link copied')).toBeInTheDocument()
  })

  it('says when a stretch link no longer has votes', async () => {
    at('/s/nowhere', <StretchPage />, '/s/:stretch')
    expect(await screen.findByRole('heading', { name: 'This stretch has no votes right now' })).toBeInTheDocument()
  })

  it('invites the first route in a province with no votes (W08)', async () => {
    at('/map/northern', <NoVotes province="northern" />, '/map/:province')
    expect(await screen.findByRole('heading', { name: 'No votes in Northern Province yet' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Add the first route/ })).toHaveAttribute('href', '/add')
  })

  it('shows nothing broken before anything is published', async () => {
    vi.stubEnv('VITE_DATA_URL', '')
    resetRankings()
    at('/leaderboard', <LeaderboardPage />, '/leaderboard')
    expect(await screen.findByText(/No rankings yet/)).toBeInTheDocument()
    expect(fetch).not.toHaveBeenCalled()
  })
})
