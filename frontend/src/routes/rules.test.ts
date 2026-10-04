import { describe, expect, it } from 'vitest'
import { distanceM, formatKm, positionAlong, waypointIndex } from './geometry'
import { formatWait, lockedUntil, placeInSlot } from './myRoutes'

const LINE = [
  [79.85, 6.93],
  [79.86, 6.93],
  [79.87, 6.93],
]

describe('waypoints', () => {
  it('measures how far along the route a point lies', () => {
    expect(positionAlong(LINE, { lat: 6.931, lon: 79.855 })).toBeCloseTo(0.005)
    expect(positionAlong(LINE, { lat: 6.929, lon: 79.865 })).toBeCloseTo(0.015)
  })

  it('puts a new waypoint between the ones it falls between', () => {
    const waypoints = [
      { lat: 6.93, lon: 79.852 },
      { lat: 6.93, lon: 79.868 },
    ]
    expect(waypointIndex(LINE, waypoints, { lat: 6.93, lon: 79.86 })).toBe(1)
    expect(waypointIndex(LINE, waypoints, { lat: 6.93, lon: 79.851 })).toBe(0)
    expect(waypointIndex(LINE, waypoints, { lat: 6.93, lon: 79.869 })).toBe(2)
    expect(waypointIndex(LINE, [], { lat: 6.93, lon: 79.86 })).toBe(0)
  })
})

describe('distances', () => {
  it('measures and formats metres', () => {
    expect(distanceM({ lat: 6.93, lon: 79.85 }, { lat: 6.93, lon: 79.86 })).toBeCloseTo(1104, -1)
    expect(formatKm(850)).toBe('850 m')
    expect(formatKm(36_840)).toBe('36.8 km')
  })
})

describe('slots, as the API places them', () => {
  const routes = (...slots: [number, number][]) => slots.map(([id, slot]) => ({ id, slot }))

  it('takes a free slot', () => {
    expect(placeInSlot(routes([10, 1]), null, 2)).toEqual(new Map([[10, 1], [-1, 2]]))
  })

  it('pushes the others down when the slot is taken', () => {
    expect(placeInSlot(routes([10, 1], [11, 2]), null, 1)).toEqual(new Map([[-1, 1], [10, 2], [11, 3]]))
  })

  it('moves routes up when there is no room below', () => {
    expect(placeInSlot(routes([10, 2], [11, 3]), null, 2)).toEqual(new Map([[10, 1], [-1, 2], [11, 3]]))
  })

  it('has no room for a fourth route', () => {
    expect(placeInSlot(routes([10, 1], [11, 2], [12, 3]), null, 2)).toBeNull()
  })

  it('moves an existing route', () => {
    expect(placeInSlot(routes([10, 1], [11, 2], [12, 3]), 12, 1)).toEqual(new Map([[12, 1], [10, 2], [11, 3]]))
  })
})

describe('cooldown', () => {
  const now = Date.parse('2026-10-04T10:00:00Z')

  it('is locked until the time the API gives', () => {
    const until = lockedUntil({ slot: 1, lockedUntil: '2026-10-05T03:42:00Z' }, now)
    expect(until?.toISOString()).toBe('2026-10-05T03:42:00.000Z')
    expect(formatWait(until!, now)).toEqual({ hours: 17, minutes: 42, text: '17 h 42 min' })
  })

  it('can change during the 15-minute grace window and after the lock ends', () => {
    expect(lockedUntil({ slot: 1, lockedUntil: '2026-10-05T09:55:00Z', graceUntil: '2026-10-04T10:10:00Z' }, now)).toBeNull()
    expect(lockedUntil({ slot: 1, lockedUntil: '2026-10-04T09:00:00Z' }, now)).toBeNull()
    expect(lockedUntil({ slot: 1 }, now)).toBeNull()
  })
})
