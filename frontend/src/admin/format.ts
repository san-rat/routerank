import type { RouteSummary } from '../api/client'
import { formatKm } from '../routes/geometry'
import { when } from './session'

export const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`

/** A vote's length and what it extends: "1.4 km · extends bus 99" */
export function voteMeta(r: RouteSummary) {
  return `${formatKm(r.lengthM)}${r.kind === 'extension' ? ` · extends bus ${r.busNumber}` : ''}`
}

/** A vote as one line: "#1 Thummulla → Havelock · 1.4 km · extends bus 99" */
export function voteLine(r: RouteSummary) {
  return `#${r.slot} ${r.name} · ${voteMeta(r)}`
}

/** What releasing does to an account's votes, which only count once the account is old enough */
export function releaseNote(liveAt: string) {
  return new Date(liveAt) > new Date()
    ? `Their votes count from ${when(liveAt)}, when the account's votes start counting.`
    : 'Their votes count again from the next scoring run.'
}
