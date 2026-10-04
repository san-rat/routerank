import { useState } from 'react'
import type { BusCheck, Preview } from '../api/client'
import radioOff from '../assets/icons/radio-off.svg'
import radioOn from '../assets/icons/radio-on.svg'
import { formatKm } from './geometry'

/**
 * The Bus check (W18, and W18b when bus + new part is over 40 km): extend the bus route, or keep the route as a
 * new one. Only the new part of an extension earns points.
 */
export function BusCheckSheet({ preview, bus, onChoose }: {
  preview: Preview
  bus: BusCheck
  /** The bus route to extend, or undefined to keep it as a new route */
  onChoose: (extend: number | undefined) => void
}) {
  const longer = Math.max(preview.lengthOutM, preview.lengthBackM)
  const tooLong = bus.problems.find((p) => p.code === 'EXTENSION_TOO_LONG')
  const extendOverlap = bus.problems.find((p) => p.code === 'OVERLAP')
  const canExtend = bus.problems.length === 0
  const newOverlap = preview.problems.find((p) => p.code === 'OVERLAP')
  const canKeep = preview.problems.length === 0
  const [choice, setChoice] = useState<'extend' | 'new' | null>(canExtend ? 'extend' : canKeep ? 'new' : null)

  // The length bar: bus and new part out of 40 km, or out of the total when it's over
  const scale = Math.max(bus.totalM, bus.limitM)
  const busShare = (bus.busLengthM / scale) * 100
  const newShare = (bus.newLengthM / scale) * 100

  return (
    <section className="route-sheet bus-check" aria-label={`Bus ${bus.number} already runs part of this`}>
      <div className="grabber" />
      <div className="bus-check-header">
        <span className="bus-badge">{bus.number}</span>
        <div>
          <h2>Bus {bus.number} already runs part of this</h2>
          <p>
            {bus.busName} ({formatKm(bus.busLengthM)})
          </p>
        </div>
      </div>

      <div className="bus-options" role="radiogroup" aria-label="Extend the bus route or keep a new route">
        <button
          type="button"
          role="radio"
          aria-checked={choice === 'extend'}
          disabled={!canExtend}
          className={`bus-option${choice === 'extend' ? ' selected' : ''}${canExtend ? '' : ' blocked'}`}
          onClick={() => setChoice('extend')}
        >
          <img src={choice === 'extend' ? radioOn : radioOff} alt="" width={22} height={22} />
          <span className="bus-option-text">
            <span className="bus-option-title">
              <strong>
                Extend Route {bus.number} to {bus.newEnd}
              </strong>
              {tooLong ? <span className="tag amber">Over 40 km</span> : canExtend && <span className="tag teal">Suggested</span>}
            </span>
            <span>
              {tooLong
                ? `Bus ${bus.number} (${formatKm(bus.busLengthM)}) + new part (${formatKm(bus.newLengthM)}) = ${formatKm(bus.totalM)}, over the 40 km max.`
                : `Counts as one route: bus ${bus.number} (${formatKm(bus.busLengthM)}) + new part (${formatKm(bus.newLengthM)}) = ${formatKm(bus.totalM)} of the 40 km max. Only the new part earns points.`}
            </span>
            <span className="length-check" aria-hidden="true">
              <span className="bus" style={{ width: `${busShare}%` }} />
              <span className={tooLong ? 'over' : 'new'} style={{ width: `${newShare}%` }} />
            </span>
            <span className="length-legend">
              <span className="bus">
                Bus {bus.number} · {formatKm(bus.busLengthM)}
              </span>
              <span className={tooLong ? 'over' : 'new'}>+ {formatKm(bus.newLengthM)} new</span>
              <strong className={tooLong ? 'over' : undefined}>
                {formatKm(bus.totalM).replace(' km', '')} / 40 km
              </strong>
            </span>
            {extendOverlap && (
              <span className="locked-text">Its new part shares road with your #{extendOverlap.slot}.</span>
            )}
          </span>
        </button>

        <button
          type="button"
          role="radio"
          aria-checked={choice === 'new'}
          disabled={!canKeep}
          className={`bus-option${choice === 'new' ? ' selected' : ''}${canKeep ? '' : ' blocked'}`}
          onClick={() => setChoice('new')}
        >
          <img src={choice === 'new' ? radioOn : radioOff} alt="" width={22} height={22} />
          <span className="bus-option-text">
            <span className="bus-option-title">
              <strong>Keep it as a new route</strong>
            </span>
            <span>
              {newOverlap
                ? `As a new route it shares road with your #${newOverlap.slot}.`
                : tooLong
                  ? `Your route alone is ${formatKm(longer)}, so it can still be saved as a new route.`
                  : `All ${formatKm(longer)} earns points, including the road bus ${bus.number} already covers. Also capped at 40 km.`}
            </span>
          </span>
        </button>
      </div>

      <button
        type="button"
        className="primary-button"
        disabled={choice === null}
        onClick={() => onChoose(choice === 'extend' ? bus.busRouteId : undefined)}
      >
        Continue
      </button>
    </section>
  )
}
