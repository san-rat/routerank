import { useEffect, useRef, useState } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router'
import chevronDown from '../assets/icons/chevron-down-dark.svg'
import info from '../assets/icons/info-20.svg'
import { ProvinceSheet } from '../map/ProvincePicker'
import { DEFAULT_PROVINCE, provinceBySlug } from '../map/provinces'
import { formatNumber, loadFile, type Manifest, type Page, updatedAt, useManifest } from './data'
import { LeaderRow } from './MapRankings'

/** /leaderboard (W10) and /leaderboard/:province (W11): the top 20, then 20 more as the list scrolls */
export function LeaderboardPage() {
  const { province } = useParams()
  const manifest = useManifest()
  const navigate = useNavigate()
  const [choosing, setChoosing] = useState(false)
  const current = province ? provinceBySlug(province) : undefined
  if (province && !current) return <Navigate to={`/leaderboard/${DEFAULT_PROVINCE}`} replace />
  const m = manifest.kind === 'loaded' ? manifest.manifest : undefined
  const entry = current && m ? m.provinces[current.slug] : undefined

  return (
    <main className="page leaderboard">
      <div className="leaderboard-header">
        <div>
          <h1>Leaderboard</h1>
          <p>
            <i className="live-dot" aria-hidden="true" />
            Updated every 30 min{m ? ` · last at ${updatedAt(m)}` : ''}
          </p>
        </div>
        <Link to="/how-it-works" className="round-button" aria-label="How ranking works">
          <img src={info} alt="" width={20} height={20} />
        </Link>
      </div>
      <nav className="segmented" aria-label="Leaderboard">
        <Link to="/leaderboard" className={province ? undefined : 'active'} aria-current={province ? undefined : 'page'}>
          Overall
        </Link>
        <Link to={`/leaderboard/${DEFAULT_PROVINCE}`} className={province ? 'active' : undefined}
          aria-current={province ? 'page' : undefined}>
          By province
        </Link>
      </nav>
      {current && (
        <button type="button" className="province-select" onClick={() => setChoosing(true)} aria-haspopup="dialog">
          <span>
            <strong>{current.name} Province</strong>
            {entry && (
              <small>
                {entry.stretches > 0
                  ? `${formatNumber(entry.stretches)} stretches · ${formatNumber(entry.people)} people voted`
                  : 'No votes yet'}
              </small>
            )}
          </span>
          <img src={chevronDown} alt="" width={20} height={20} />
        </button>
      )}
      {choosing && current && (
        <ProvinceSheet current={current} onClose={() => setChoosing(false)} onChoose={(p) => {
          setChoosing(false)
          navigate(`/leaderboard/${p.slug}`)
        }} />
      )}
      {manifest.kind === 'loading' && <Skeleton rows={6} />}
      {manifest.kind === 'error' && <p role="alert">Couldn't load the rankings. Please try again later.</p>}
      {manifest.kind === 'none' && <p className="empty-list">No rankings yet. They appear after the first votes are counted.</p>}
      {m && (
        <Ranked key={current?.slug ?? 'overall'} manifest={m}
          pages={current ? entry?.pages ?? [] : m.overall.pages} overall={!current} />
      )}
    </main>
  )
}

function Skeleton({ rows }: { rows: number }) {
  return (
    <ol className="leaderboard-list" aria-busy="true">
      {Array.from({ length: rows }, (_, i) => (
        <li key={i} className="leader-row loading">
          <span className="rank" />
          <span className="leader-main">
            <span className="line long" />
            <span className="line" />
          </span>
        </li>
      ))}
    </ol>
  )
}

/** The ranked list, loading the next page when its end scrolls into view */
function Ranked({ manifest, pages, overall }: { manifest: Manifest; pages: string[]; overall: boolean }) {
  const [loaded, setLoaded] = useState<Page[]>([])
  const [failed, setFailed] = useState(false)
  const end = useRef<HTMLDivElement>(null)
  const next = loaded.length < pages.length ? pages[loaded.length] : null

  useEffect(() => {
    if (!next) return
    const target = end.current
    let cancelled = false
    const load = () =>
      loadFile<Page>(next).then(
        (page) => !cancelled && setLoaded((l) => (l.length < pages.length && !l.includes(page) ? [...l, page] : l)),
        () => !cancelled && setFailed(true),
      )
    // The first page straight away; later ones when the list's end comes near
    if (loaded.length === 0 || !target || !('IntersectionObserver' in window)) {
      load()
      return () => {
        cancelled = true
      }
    }
    const observer = new IntersectionObserver((seen) => seen.some((s) => s.isIntersecting) && load(), {
      rootMargin: '400px',
    })
    observer.observe(target)
    return () => {
      cancelled = true
      observer.disconnect()
    }
  }, [next, loaded.length, pages.length])

  if (pages.length === 0) {
    return (
      <p className="empty-list">
        No stretches on the leaderboard yet. A stretch joins once it is 1 km long with 3 people behind it; every
        stretch with votes shows on the map.
      </p>
    )
  }
  const entries = loaded.flatMap((p) => p.entries)
  const top = entries[0]?.points ?? 0
  return (
    <>
      <ol className="leaderboard-list">
        {entries.map((e) => (
          <LeaderRow key={e.slug} entry={e} top={top} manifest={manifest} showProvince={overall} />
        ))}
      </ol>
      {next && !failed && <Skeleton rows={entries.length === 0 ? 6 : 1} />}
      {failed && <p role="alert">Couldn't load more. Please try again later.</p>}
      <div ref={end} />
    </>
  )
}
