import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { useMap } from '../app/mapContext'
import { DESKTOP, useMediaQuery } from '../app/useMediaQuery'
import close from '../assets/icons/close.svg'
import extendTeal from '../assets/icons/extend-22.svg'
import extendWhite from '../assets/icons/extend-white.svg'
import { provinceBySlug, provinces } from '../map/provinces'
import { formatNumber } from '../rankings/data'
import { setFocus, useFocus } from '../rankings/focus'
import { draftActions } from '../routes/draft'
import { formatKm } from '../routes/geometry'
import { setShown, useBusRoutes, useShown } from './busData'
import { busLines, type BusLayer } from './busLayer'

/** The "● Bus routes" chip (W02, W05): shows or hides the red layer */
export function BusChip() {
  const routes = useBusRoutes()
  const on = useShown()
  if (!routes?.length) return null
  return (
    <button type="button" className={`chip-toggle${on ? '' : ' off'}`} aria-pressed={on} onClick={() => setShown(!on)}>
      <span className="chip-dot bus" aria-hidden="true" /> Bus routes
    </button>
  )
}

const BUS_FOCUS = 'bus:'

/** The red bus layer; tapping a route opens it (/bus/:number) */
export function BusesOnMap() {
  const map = useMap()
  const routes = useBusRoutes()
  const visible = useShown()
  const focus = useFocus()
  const navigate = useNavigate()
  const [layer, setLayer] = useState<BusLayer | null>(null)

  useEffect(() => {
    if (!map) return
    let current: BusLayer | null = null
    let cancelled = false
    // The drawing code comes with MapLibre's chunk, already loaded whenever a map is on screen
    import('./busLayer').then(({ createBusLayer }) => {
      if (cancelled) return
      current = createBusLayer(map, (number) => navigate(`/bus/${encodeURIComponent(number)}`))
      setLayer(current)
    })
    return () => {
      cancelled = true
      current?.remove()
      setLayer(null)
    }
  }, [map, navigate])

  const selected = focus?.slug.startsWith(BUS_FOCUS) ? focus.slug.slice(BUS_FOCUS.length) : null
  useEffect(() => {
    layer?.update(busLines(routes ?? []), selected, visible)
  }, [layer, routes, selected, visible])

  return null
}

/** The province a point is in, by the provinces' boxes (good enough to choose which province's map to show) */
function provinceAt([lon, lat]: [number, number]): string {
  return provinces.find((p) => lon >= p.bbox[0] && lon <= p.bbox[2] && lat >= p.bbox[1] && lat <= p.bbox[3])?.slug ?? 'western'
}

/** /bus/:number — W05: a bus route, the towns it passes and its most-wanted extension */
export function BusPage() {
  const { number = '' } = useParams()
  const routes = useBusRoutes()
  const navigate = useNavigate()
  const desktop = useMediaQuery(DESKTOP)
  const bus = routes?.find((r) => r.number === number)

  useEffect(() => {
    if (bus) setFocus({ slug: `${BUS_FOCUS}${bus.number}`, province: provinceAt(bus.start), bbox: bus.bbox })
  }, [bus])
  useEffect(() => () => setFocus(null), [])

  if (!routes) return <section className="stretch-sheet" aria-busy="true" />
  if (!bus) {
    return (
      <section className="stretch-sheet" aria-label="Bus route not found">
        {!desktop && <div className="grabber" />}
        <h2>No bus route {number} on RouteRank</h2>
        <p>It may have been withdrawn. Bus routes on the map update every 30 minutes.</p>
        <Link className="primary-button" to="/map/western">
          Back to the map
        </Link>
      </section>
    )
  }

  const province = provinceAt(bus.start)
  function propose() {
    // Start where the most-wanted extension leaves the bus, or at the bus's end
    const wanted = bus!.wanted
    const atStart = wanted ? wanted.name.split(' → ').includes(bus!.startName) : false
    const [lon, lat] = atStart ? bus!.start : bus!.end
    draftActions.reset()
    draftActions.setStart({ lon, lat }, atStart ? bus!.startName : bus!.endName)
    navigate('/add')
  }

  const wanted = bus.wanted
  const wantedEnd = wanted?.name.split(' → ').find((n) => n !== bus.startName && n !== bus.endName)
  return (
    <section className="stretch-sheet bus-sheet" aria-label={`Bus ${bus.number}: ${bus.name}`}>
      {!desktop && <div className="grabber" />}
      <div className="stretch-header">
        <span className="bus-badge">{bus.number}</span>
        <div>
          <h2>{bus.name}</h2>
          <p>Existing bus route · {formatKm(Math.max(bus.lengthOutM, bus.lengthBackM))}</p>
        </div>
        <button type="button" className="close-button" onClick={() => navigate(`/map/${province}`)} aria-label="Close">
          <img src={close} alt="" width={20} height={20} />
        </button>
      </div>
      {bus.towns.length > 0 && (
        <div className="bus-via">
          <span>Via</span>
          <strong>{bus.towns.join(' · ')}</strong>
        </div>
      )}
      {wanted && (
        <Link className="bus-wanted" to={`/s/${wanted.slug}`}>
          <img src={extendTeal} alt="" width={22} height={22} />
          <span>
            <strong>Most-wanted extension: → {wantedEnd ?? wanted.name}</strong>
            <span>
              {wanted.rankProvince
                ? `Busiest stretch #${wanted.rankProvince} in ${provinceBySlug(wanted.province)?.name ?? wanted.province} Province · `
                : ''}
              {formatNumber(wanted.points)} pts
            </span>
          </span>
        </Link>
      )}
      <button type="button" className="primary-button" onClick={propose}>
        <img src={extendWhite} alt="" width={18} height={18} /> Propose an extension
      </button>
    </section>
  )
}
