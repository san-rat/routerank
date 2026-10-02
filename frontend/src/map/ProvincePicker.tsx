import { useState } from 'react'
import { useNavigate } from 'react-router'
import check from '../assets/icons/check.svg'
import chevronDown from '../assets/icons/chevron-down.svg'
import chevronRight from '../assets/icons/chevron-right.svg'
import { provinces, type Province } from './provinces'

// Order of the Figma W07 sheet (most voters first); counts arrive with the rankings in Phase 5
const ORDER = ['western', 'central', 'southern', 'north-western', 'sabaragamuwa', 'uva', 'north-central', 'eastern', 'northern']
const sorted = [...provinces].sort((a, b) => ORDER.indexOf(a.slug) - ORDER.indexOf(b.slug))

export function ProvincePicker({ current }: { current: Province }) {
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()

  function choose(p: Province) {
    setOpen(false)
    navigate(`/map/${p.slug}`)
  }

  return (
    <>
      <button className="chip-province" onClick={() => setOpen(true)} aria-haspopup="dialog">
        {current.name} Province
        <img src={chevronDown} alt="" width={16} height={16} />
      </button>
      {open && (
        <>
          <div className="scrim" onClick={() => setOpen(false)} />
          <div className="sheet" role="dialog" aria-modal="true" aria-labelledby="province-title">
            <div className="grabber" />
            <div>
              <h2 id="province-title">Choose a province</h2>
              <p>Or just pan the map — the next province loads as you move.</p>
            </div>
            <div className="province-list">
              {sorted.map((p) => {
                const selected = p.slug === current.slug
                return (
                  <button key={p.slug} className={`province-row${selected ? ' selected' : ''}`}
                    onClick={() => choose(p)} aria-current={selected ? 'true' : undefined}>
                    <span>{p.name} Province</span>
                    {selected
                      ? <img src={check} alt="" width={20} height={20} />
                      : <img src={chevronRight} alt="" width={18} height={18} />}
                  </button>
                )
              })}
            </div>
          </div>
        </>
      )}
    </>
  )
}
