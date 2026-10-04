import { useState } from 'react'
import { createPortal } from 'react-dom'
import { useNavigate } from 'react-router'
import check from '../assets/icons/check.svg'
import chevronDown from '../assets/icons/chevron-down.svg'
import chevronRight from '../assets/icons/chevron-right.svg'
import { formatNumber, useManifest } from '../rankings/data'
import { provinces, type Province } from './provinces'

// Order of the Figma W07 sheet before there are counts; with rankings, the provinces with most voters come first
const ORDER = ['western', 'central', 'southern', 'north-western', 'sabaragamuwa', 'uva', 'north-central', 'eastern', 'northern']

/** W07: choose a province, with how many people voted there and its stretches */
export function ProvinceSheet({ current, onChoose, onClose }: {
  current: Province
  onChoose: (p: Province) => void
  onClose: () => void
}) {
  const manifest = useManifest()
  const counts = manifest.kind === 'loaded' ? manifest.manifest.provinces : undefined
  const sorted = [...provinces].sort((a, b) =>
    (counts?.[b.slug]?.people ?? 0) - (counts?.[a.slug]?.people ?? 0) || ORDER.indexOf(a.slug) - ORDER.indexOf(b.slug))
  // On document.body, so a page's own stacking (the leaderboard) can't put the nav above it
  return createPortal(
    <>
      <div className="scrim" onClick={onClose} />
      <div className="sheet" role="dialog" aria-modal="true" aria-labelledby="province-title">
        <div className="grabber" />
        <div>
          <h2 id="province-title">Choose a province</h2>
          <p>Or just pan the map — the next province loads as you move.</p>
        </div>
        <div className="province-list">
          {sorted.map((p) => {
            const selected = p.slug === current.slug
            const entry = counts?.[p.slug]
            return (
              <button key={p.slug} className={`province-row${selected ? ' selected' : ''}`}
                onClick={() => onChoose(p)} aria-current={selected ? 'true' : undefined}>
                <span>
                  {p.name} Province
                  {counts && (
                    <small>
                      {entry && entry.stretches > 0
                        ? `${formatNumber(entry.people)} people · ${formatNumber(entry.stretches)} stretches`
                        : 'No votes yet'}
                    </small>
                  )}
                </span>
                {selected
                  ? <img src={check} alt="" width={20} height={20} />
                  : <img src={chevronRight} alt="" width={18} height={18} />}
              </button>
            )
          })}
        </div>
      </div>
    </>,
    document.body,
  )
}

export function ProvincePicker({ current }: { current: Province }) {
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()

  return (
    <>
      <button className="chip-province" onClick={() => setOpen(true)} aria-haspopup="dialog">
        {current.name} Province
        <img src={chevronDown} alt="" width={16} height={16} />
      </button>
      {open && (
        <ProvinceSheet current={current} onClose={() => setOpen(false)} onChoose={(p) => {
          setOpen(false)
          navigate(`/map/${p.slug}`)
        }} />
      )}
    </>
  )
}
