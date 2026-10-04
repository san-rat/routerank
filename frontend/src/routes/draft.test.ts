import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Preview } from '../api/client'
import { draftActions, getDraft, PREVIEW_DELAY_MS } from './draft'

const A = { lat: 6.93, lon: 79.85 }
const B = { lat: 6.9, lon: 79.87 }

function preview(over: Partial<Preview> = {}): Preview {
  return {
    points: [A, B],
    out: [
      [79.85, 6.93],
      [79.87, 6.9],
    ],
    back: [
      [79.87, 6.9],
      [79.85, 6.93],
    ],
    backLeaves: [],
    backVia: [],
    lengthOutM: 5000,
    lengthBackM: 5200,
    name: 'Pettah → Borella',
    problems: [],
    ...over,
  }
}

let fetch: ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.useFakeTimers()
  draftActions.reset()
  fetch = vi.fn(async () => new Response(JSON.stringify(preview()), { status: 200 }))
  vi.stubGlobal('fetch', fetch)
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('the route draft', () => {
  it('asks for one preview after changes settle, never per change', async () => {
    draftActions.setStart(A, 'Pettah')
    draftActions.setEnd(B, 'Borella')
    draftActions.moveWaypoint(0, A)
    expect(getDraft().previewing).toBe(true)
    expect(fetch).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(PREVIEW_DELAY_MS)
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch.mock.calls[0][0]).toBe('/api/routes/preview')
    expect(getDraft().preview?.name).toBe('Pettah → Borella')
  })

  it('snaps a place picked from search to the nearest main road without asking', async () => {
    const road = { lat: 6.9301, lon: 79.8502 }
    fetch
      .mockResolvedValueOnce(new Response(JSON.stringify(preview({ problems: [{ code: 'SIDE_ROAD', point: 'start', nearest: road }] }))))
      .mockResolvedValueOnce(new Response(JSON.stringify(preview())))
    draftActions.setStart(A, 'Pettah', true)
    draftActions.setEnd(B, 'Borella')
    await vi.advanceTimersByTimeAsync(PREVIEW_DELAY_MS)
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(JSON.parse(fetch.mock.calls[1][1].body).start).toEqual(road)
    expect(getDraft().start).toEqual(road)
  })

  it('keeps a tapped side-road point so the user can choose (W22)', async () => {
    fetch.mockResolvedValueOnce(
      new Response(JSON.stringify(preview({ problems: [{ code: 'SIDE_ROAD', point: 'start', nearest: B }] }))),
    )
    draftActions.setStart(A, 'Pin on map')
    draftActions.setEnd(B, 'Borella')
    await vi.advanceTimersByTimeAsync(PREVIEW_DELAY_MS)
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(getDraft().preview?.problems[0].code).toBe('SIDE_ROAD')
  })

  it('undoes the last change and starts fresh on reset', async () => {
    draftActions.setStart(A, 'Pettah')
    draftActions.setEnd(B, 'Borella')
    draftActions.undo()
    expect(getDraft().end).toBeUndefined()
    expect(getDraft().start).toEqual(A)
    draftActions.reset(2)
    expect(getDraft().start).toBeUndefined()
    expect(getDraft().preferredSlot).toBe(2)
  })

  it('allows at most 8 waypoints', async () => {
    draftActions.setStart(A, 'Pettah')
    draftActions.setEnd(B, 'Borella')
    await vi.advanceTimersByTimeAsync(PREVIEW_DELAY_MS)
    for (let i = 0; i < 8; i++) expect(draftActions.addWaypoint({ lat: 6.92, lon: 79.86 })).toBe(true)
    expect(draftActions.addWaypoint({ lat: 6.92, lon: 79.86 })).toBe(false)
    expect(getDraft().waypoints).toHaveLength(8)
  })
})
