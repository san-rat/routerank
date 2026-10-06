import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import close from '../assets/icons/close.svg'
import pin from '../assets/icons/pin-30.svg'
import plus from '../assets/icons/plus.svg'
import share from '../assets/icons/share.svg'
import { DESKTOP, useMediaQuery } from '../app/useMediaQuery'
import { provinceBySlug } from '../map/provinces'
import { draftActions } from '../routes/draft'
import { formatKm } from '../routes/geometry'
import {
  band,
  type Detail,
  type Details,
  formatNumber,
  HEAT,
  type Manifest,
  type Page,
  type SlugIndex,
  updatedAt,
  useFile,
  useManifest,
} from './data'
import { setFocus } from './focus'

/** W02: what the colours mean */
export function Legend() {
  const manifest = useManifest()
  if (manifest.kind !== 'loaded') return null
  return (
    <div className="legend" aria-label="Map legend">
      <strong>Votes on this stretch</strong>
      <span className="heat-scale" aria-hidden="true" />
      <span className="legend-ends">
        <span>Fewer</span>
        <span>More</span>
      </span>
      <small>Updated every 30 min</small>
    </div>
  )
}

/** W08: a province nobody has voted in yet */
export function NoVotes({ province }: { province: string }) {
  const manifest = useManifest()
  const entry = manifest.kind === 'loaded' ? manifest.manifest.provinces[province] : undefined
  if (!entry || entry.stretches > 0) return null
  return (
    <section className="no-votes" aria-label="No votes yet">
      <span className="no-votes-icon">
        <img src={pin} alt="" width={30} height={30} />
      </span>
      <h2>No votes in {entry.name} Province yet</h2>
      <p>Be the first to suggest where a new bus should run here.</p>
      <Link className="primary-button" to="/add" onClick={() => draftActions.reset()}>
        <img src={plus} alt="" width={18} height={18} /> Add the first route
      </Link>
    </section>
  )
}

/** Desktop panel on the map: the province's top stretches, its counts and the legend */
export function ProvinceSummary({ province }: { province: string }) {
  const manifest = useManifest()
  const name = provinceBySlug(province)?.name ?? province
  const entry = manifest.kind === 'loaded' ? manifest.manifest.provinces[province] : undefined
  const page = useFile<Page>(entry?.pages[0])
  return (
    <main className="page province-summary">
      <h1>{name} Province</h1>
      {manifest.kind === 'loading' && <p>Loading rankings…</p>}
      {(manifest.kind === 'none' || manifest.kind === 'error') && (
        <p>{manifest.kind === 'none' ? 'Rankings appear after the first votes are counted.' : "Couldn't load the rankings."}</p>
      )}
      {manifest.kind === 'loaded' && entry && (
        <>
          <p>
            {entry.stretches === 0
              ? 'No votes yet'
              : `${formatNumber(entry.people)} people · ${formatNumber(entry.stretches)} stretches`}{' '}
            · updated {updatedAt(manifest.manifest)}
          </p>
          {page && page.entries.length > 0 && (
            <ol className="leaderboard-list compact">
              {page.entries.slice(0, 10).map((e) => (
                <LeaderRow key={e.slug} entry={e} top={page.entries[0].points} manifest={manifest.manifest} />
              ))}
            </ol>
          )}
          {entry.stretches > 0 && entry.ranked === 0 && (
            <p className="footnote">Stretches join the leaderboard once they are 1 km long with 3 people behind them.</p>
          )}
          {entry.ranked > 0 && (
            <Link className="text-link" to={`/leaderboard/${province}`}>
              See the full leaderboard
            </Link>
          )}
        </>
      )}
    </main>
  )
}

/** One leaderboard row (W10, W11) */
export function LeaderRow({ entry, top, manifest, showProvince = true }: {
  entry: Page['entries'][number]
  top: number
  manifest: Manifest
  showProvince?: boolean
}) {
  const ratio = top > 0 ? entry.points / top : 0
  const province = provinceBySlug(entry.province)?.name ?? entry.province
  const colour = HEAT[band(entry.points, manifest.bands)]
  return (
    <li>
      <Link to={`/s/${entry.slug}`} className="leader-row">
        <span className={`rank${entry.rank <= 3 ? ' top' : ''}`}>{entry.rank}</span>
        <span className="leader-main">
          <strong>{entry.name}</strong>
          <span>
            {showProvince ? `${province} · ` : ''}
            {formatKm(entry.lengthM)}
            {entry.extendsBus && <span className="tag red extends">Extends {entry.extendsBus}</span>}
          </span>
          <span className="score-bar">
            <span style={{ width: `${Math.max(4, ratio * 100)}%`, background: colour }} />
          </span>
        </span>
        <span className="leader-score">
          <strong>{formatNumber(entry.points)} pts</strong>
          <small>{formatNumber(entry.people)} people</small>
        </span>
      </Link>
    </li>
  )
}

type Found =
  | { kind: 'loading' }
  | { kind: 'missing' }
  | { kind: 'found'; slug: string; province: string; detail: Detail; details: Details }

/** Finds a stretch from its link slug: the slug index names its province, whose details file holds it */
function useStretch(slug: string): Found {
  const manifest = useManifest()
  const m = manifest.kind === 'loaded' ? manifest.manifest : undefined
  const index = useFile<SlugIndex>(m?.slugs)
  const target = index?.[slug]
  const details = useFile<Details>(target && m ? m.provinces[target[1]]?.details : undefined)
  if (manifest.kind === 'none' || manifest.kind === 'error' || index === null || details === null) return { kind: 'missing' }
  if (index && !target) return { kind: 'missing' }
  const detail = target && details?.stretches[target[0]]
  if (target && details && !detail) return { kind: 'missing' }
  return detail && details ? { kind: 'found', slug: target[0], province: target[1], detail, details } : { kind: 'loading' }
}

/** /s/:stretch — W04: a ranked stretch, highlighted on the map */
export function StretchPage() {
  const { stretch = '' } = useParams()
  const found = useStretch(stretch)
  const navigate = useNavigate()
  const desktop = useMediaQuery(DESKTOP)
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    if (found.kind !== 'found') return
    // An old link: show the stretch's own address
    if (found.slug !== stretch) navigate(`/s/${found.slug}`, { replace: true })
    // On a whole road, the map shows all of it
    const road = found.detail.along ? found.details.roads?.[found.detail.along] : undefined
    setFocus({ slug: found.slug, province: found.province, bbox: road?.bbox ?? found.detail.bbox, road: road?.stretches })
  }, [found, stretch, navigate])
  useEffect(() => () => setFocus(null), [])

  if (found.kind === 'loading') return <section className="stretch-sheet" aria-busy="true" />
  if (found.kind === 'missing') {
    return (
      <section className="stretch-sheet" aria-label="Stretch not found">
        {!desktop && <div className="grabber" />}
        <h2>This stretch has no votes right now</h2>
        <p>Its votes may have moved or been removed since the link was shared. Rankings update every 30 minutes.</p>
        <Link className="primary-button" to="/map/western">
          Back to the map
        </Link>
      </section>
    )
  }

  const { detail: d, province } = found
  const road = d.along ? found.details.roads?.[d.along] : undefined
  const [first, second, third] = d.votes
  const total = first * 3 + second * 2 + third || 1
  const [from, to] = d.name.split(' → ')

  function vote() {
    draftActions.reset()
    draftActions.setStart({ lon: d.ends[0][0], lat: d.ends[0][1] }, to ? from : d.name)
    draftActions.setEnd({ lon: d.ends[1][0], lat: d.ends[1][1] }, to ?? d.name)
    navigate('/add/route')
  }

  async function shareLink() {
    const url = `${location.origin}/s/${found.kind === 'found' ? found.slug : stretch}`
    if (navigator.share) {
      await navigator.share({ title: `${d.name} on RouteRank`, url }).catch(() => undefined)
      return
    }
    // Says "Link copied" only when it was
    const copiedOk = await navigator.clipboard?.writeText(url).then(() => true, () => false)
    if (!copiedOk) return
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <section className="stretch-sheet" aria-label={d.name}>
      {!desktop && <div className="grabber" />}
      <div className="stretch-header">
        <span className={`rank-badge${d.rankOverall ? '' : ' unranked'}`}>{d.rankOverall ? `#${d.rankOverall}` : '–'}</span>
        <div>
          <h2>{d.name}</h2>
          <p>
            {[d.road, formatKm(d.lengthM)].filter(Boolean).join(' · ')}
            {d.extendsBus && (
              <Link className="tag red extends" to={`/bus/${encodeURIComponent(d.extendsBus)}`}>
                Extends {d.extendsBus}
              </Link>
            )}
          </p>
        </div>
        <button type="button" className="close-button" onClick={() => navigate(`/map/${province}`)} aria-label="Close">
          <img src={close} alt="" width={20} height={20} />
        </button>
      </div>
      {road && (
        <div className="whole-road">
          <strong>
            Part of {road.name} · {formatKm(road.lengthM)}
          </strong>
          <ol>
            {road.stretches.map((slug) => {
              const s = found.details.stretches[slug]
              if (!s) return null
              const text = `${s.name} · ${formatNumber(s.points)} pts`
              return (
                <li key={slug}>
                  {slug === found.slug ? <span aria-current="true">{text}</span> : <Link to={`/s/${slug}`}>{text}</Link>}
                </li>
              )
            })}
          </ol>
          <small>Each stretch is ranked on its own.</small>
        </div>
      )}
      <div className="stats">
        <span>
          <strong>{formatNumber(d.points)}</strong>points
        </span>
        <span>
          <strong>{formatNumber(d.people)}</strong>people
        </span>
        <span>
          <strong>{d.rankProvince ? `#${d.rankProvince}` : '–'}</strong>
          {d.rankProvince ? `${d.province} Prov.` : 'Not ranked yet'}
        </span>
      </div>
      {!d.rankOverall && (
        <p className="footnote">Stretches are ranked once they are 1 km long with 3 people behind them.</p>
      )}
      <div className="breakdown">
        <strong>Where the points come from</strong>
        <span className="split-bar" aria-hidden="true">
          {first > 0 && <span style={{ flexGrow: first * 3 / total, background: HEAT[4] }} />}
          {second > 0 && <span style={{ flexGrow: second * 2 / total, background: HEAT[2] }} />}
          {third > 0 && <span style={{ flexGrow: third / total, background: HEAT[0] }} />}
        </span>
        <span className="split-legend">
          <span>
            <i style={{ background: HEAT[4] }} />
            {formatNumber(first)} × #1 = {formatNumber(first * 3)}
          </span>
          <span>
            <i style={{ background: HEAT[2] }} />
            {formatNumber(second)} × #2 = {formatNumber(second * 2)}
          </span>
          <span>
            <i style={{ background: HEAT[0] }} />
            {formatNumber(third)} × #3 = {formatNumber(third)}
          </span>
        </span>
      </div>
      <div className="stretch-actions">
        <button type="button" className="primary-button" onClick={vote}>
          <img src={plus} alt="" width={18} height={18} /> Vote for this stretch
        </button>
        <button type="button" className="share-button" onClick={shareLink} aria-label="Share this stretch">
          <img src={share} alt="" width={20} height={20} />
        </button>
      </div>
      {copied && (
        <p className="toast share-toast" role="status">
          Link copied
        </p>
      )}
    </section>
  )
}
