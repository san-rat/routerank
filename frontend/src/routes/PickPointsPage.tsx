import type { MapMouseEvent } from 'maplibre-gl'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { api, type LatLon, type Place } from '../api/client'
import { useMap } from '../app/mapContext'
import close from '../assets/icons/close.svg'
import info from '../assets/icons/info.svg'
import pinTeal from '../assets/icons/pin-teal.svg'
import pinIcon from '../assets/icons/pin.svg'
import swap from '../assets/icons/swap.svg'
import { SideRoadSheet, SignInGate } from './common'
import { draftActions, ensurePreview, useDraft } from './draft'
import { loadMyRoutes, useMyRoutes } from './myRoutes'
import { useRouteLayer } from './useRouteLayer'

type Which = 'start' | 'end'

const KINDS: Record<string, string> = { city: 'City', town: 'Town', suburb: 'Suburb', village: 'Village' }

/** /add — pick the start and end by tapping a main road or searching for a place (W15, W16) */
export function PickPointsPage() {
  return (
    <SignInGate title="Add a route">
      <PickPoints />
    </SignInGate>
  )
}

function PickPoints() {
  const draft = useDraft()
  const myRoutes = useMyRoutes()
  const map = useMap()
  const navigate = useNavigate()
  const [focus, setFocus] = useState<Which>(draft.start ? 'end' : 'start')
  const [query, setQuery] = useState('')
  const [typing, setTyping] = useState(false)
  const [results, setResults] = useState<Place[]>([])
  const [searched, setSearched] = useState('')
  const focusRef = useRef(focus)
  focusRef.current = focus
  const inputs = useRef<Record<Which, HTMLInputElement | null>>({ start: null, end: null })

  useEffect(() => {
    loadMyRoutes()
    ensurePreview()
  }, [])

  // Tapping the map drops the focused point there
  useEffect(() => {
    if (!map) return
    const onClick = (e: MapMouseEvent) => {
      const point = { lat: e.lngLat.lat, lon: e.lngLat.lng }
      choose(focusRef.current, point, 'Pin on map', false)
    }
    map.on('click', onClick)
    return () => {
      map.off('click', onClick)
    }
    // choose only uses the store and setters
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map])

  // Search as the user types (prefix search on English place names)
  useEffect(() => {
    const q = query.trim()
    if (q.length < 2) {
      setResults([])
      setSearched('')
      return
    }
    const controller = new AbortController()
    const timer = setTimeout(() => {
      api.searchPlaces(q, controller.signal).then((places) => {
        setResults(places)
        setSearched(q)
      }, () => {})
    }, 250)
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [query])

  function choose(which: Which, point: LatLon, label: string, fromSearch: boolean) {
    if (which === 'start') draftActions.setStart(point, label, fromSearch)
    else draftActions.setEnd(point, label, fromSearch)
    setQuery('')
    setTyping(false)
    setResults([])
    setSearched('')
    if (which === 'start' && !draft.end) {
      setFocus('end')
      inputs.current.end?.focus()
    } else {
      inputs.current[which]?.blur()
    }
  }

  const pointProblem = draft.preview?.problems.find((p) => p.code === 'SIDE_ROAD' || p.code === 'NO_ROAD_NEARBY')
  const ready = Boolean(draft.start && draft.end && draft.preview && !draft.previewing && !pointProblem)

  // Both points on main roads: on to the route preview
  useEffect(() => {
    if (ready) navigate('/add/route')
  }, [ready, navigate])

  const drawing = useMemo(
    () => ({ out: [], backLeaves: [], start: draft.start, end: draft.end, waypoints: [] }),
    [draft.start, draft.end],
  )
  useRouteLayer(map, drawing, {})

  const routes = myRoutes.kind === 'loaded' ? myRoutes.data.routes : []
  const full = !draft.editing && routes.length >= 3

  function exit() {
    const editing = draft.editing
    draftActions.reset()
    navigate(editing ? '/me/routes' : '/')
  }

  return (
    <div className="add-route">
      <section className="route-panel" aria-label={draft.editing ? 'Edit route' : 'New route'}>
        <div className="route-panel-title">
          <button type="button" className="round-button" onClick={exit} aria-label="Cancel">
            <img src={close} alt="" width={20} height={20} />
          </button>
          <div>
            <h1>{draft.editing ? `Edit ${draft.editing.name}` : 'New route'}</h1>
            <p>{myRoutes.kind === 'loaded' ? `${routes.length} of 3 slots used` : ' '}</p>
          </div>
        </div>
        {full ? (
          <p className="panel-note" role="alert">
            All 3 slots hold a route. Remove or edit one in <a href="/me/routes">My routes</a> first.
          </p>
        ) : (
          <>
            <div className="route-fields">
              <div className="route-inputs">
                {(['start', 'end'] as const).map((which) => (
                  <label key={which} className={`route-input ${focus === which ? 'focused' : ''}`}>
                    <span className={`dot ${which}`} aria-hidden="true" />
                    <input
                      ref={(el) => {
                        inputs.current[which] = el
                      }}
                      type="search"
                      aria-label={which === 'start' ? 'Start' : 'End'}
                      placeholder={which === 'start' ? 'Where should it start?' : 'Where should it end?'}
                      value={focus === which && typing ? query : (draft[`${which}Label`] ?? '')}
                      onFocus={(e) => {
                        setFocus(which)
                        e.target.select()
                      }}
                      onChange={(e) => {
                        setFocus(which)
                        setTyping(true)
                        setQuery(e.target.value)
                      }}
                      autoComplete="off"
                    />
                  </label>
                ))}
              </div>
              <button
                type="button"
                className="round-button large"
                onClick={draftActions.swap}
                disabled={!draft.start && !draft.end}
                aria-label="Swap start and end"
              >
                <img src={swap} alt="" width={20} height={20} />
              </button>
            </div>
            <p className="route-rules">
              <img src={info} alt="" width={14} height={14} /> Main roads only · max 40 km
            </p>
          </>
        )}
      </section>

      {results.length > 0 && (
        <ul className="search-results" aria-label="Places">
          {results.map((place) => (
            <li key={`${place.name}-${place.lat}-${place.lon}`}>
              <button type="button" onClick={() => choose(focus, { lat: place.lat, lon: place.lon }, place.name, true)}>
                <span className="result-icon">
                  <img src={pinTeal} alt="" width={20} height={20} />
                </span>
                <span className="result-text">
                  <strong>{place.name}</strong>
                  <span>{KINDS[place.kind] ?? place.kind}</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {typing && searched && results.length === 0 && (
        <p className="search-empty" role="status">
          No town or village called “{searched}…”. Try another name, or tap the road on the map.
        </p>
      )}

      {results.length > 0 || (typing && searched) ? null : pointProblem?.code === 'SIDE_ROAD' && pointProblem.nearest && (pointProblem.point === 'start' || pointProblem.point === 'end') ? (
        <SideRoadSheet
          input={draft[pointProblem.point]}
          nearest={pointProblem.nearest}
          onUse={() => draftActions.useNearest(pointProblem.point as Which, pointProblem.nearest!)}
          onPickAnother={() => setFocus(pointProblem.point as Which)}
        />
      ) : pointProblem?.code === 'NO_ROAD_NEARBY' ? (
        <p className="map-hint" role="alert">
          There's no main road near that point. Tap closer to a main road.
        </p>
      ) : draft.previewFailed ? (
        <p className="map-hint" role="alert">
          Couldn't work out the route. <button type="button" onClick={draftActions.retry}>Try again</button>
        </p>
      ) : (
        !full && (
          <p className="map-hint">
            <img src={pinIcon} alt="" width={20} height={20} />
            {draft.previewing ? 'Finding the main roads…' : 'Tap a main road on the map, or search for a town'}
          </p>
        )
      )}
    </div>
  )
}
