import type { LatLon } from '../api/client'

/** A GeoJSON-style [lon, lat] pair, as the API sends lines */
export type Position = number[]

/** How far along a line (in degrees, which is fine for ordering points) the point lies when projected onto it */
export function positionAlong(line: Position[], point: LatLon): number {
  let best = Infinity
  let bestAt = 0
  let walked = 0
  for (let i = 0; i < line.length - 1; i++) {
    const [ax, ay] = line[i]
    const [bx, by] = line[i + 1]
    const dx = bx - ax
    const dy = by - ay
    const length = Math.hypot(dx, dy)
    const t = length === 0 ? 0 : Math.max(0, Math.min(1, ((point.lon - ax) * dx + (point.lat - ay) * dy) / (length * length)))
    const distance = Math.hypot(point.lon - (ax + dx * t), point.lat - (ay + dy * t))
    if (distance < best) {
      best = distance
      bestAt = walked + length * t
    }
    walked += length
  }
  return bestAt
}

/** Where a new waypoint goes among the existing ones, so the route still passes them in order */
export function waypointIndex(line: Position[], waypoints: LatLon[], point: LatLon): number {
  const at = positionAlong(line, point)
  const index = waypoints.findIndex((w) => positionAlong(line, w) > at)
  return index < 0 ? waypoints.length : index
}

/** Straight-line distance in metres (haversine) */
export function distanceM(a: LatLon, b: LatLon): number {
  const r = 6371008.8
  const rad = Math.PI / 180
  const dLat = (b.lat - a.lat) * rad
  const dLon = (b.lon - a.lon) * rad
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLon / 2) ** 2
  return 2 * r * Math.asin(Math.sqrt(h))
}

/** "36.8 km", or "850 m" under a kilometre */
export function formatKm(metres: number): string {
  return metres < 1000 ? `${Math.round(metres)} m` : `${(metres / 1000).toFixed(1)} km`
}
