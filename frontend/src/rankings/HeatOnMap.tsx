import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { useMap } from '../app/mapContext'
import { type Details, formatNumber, type Heat, useFile, useManifest } from './data'
import { useFocus } from './focus'
import type { HeatLayer } from './heatLayer'

const NO_BANDS: number[] = []

/** The vote heatmap for the province on the map; tapping a stretch opens it (/s/:slug) */
export function HeatOnMap({ province }: { province: string }) {
  const map = useMap()
  const manifest = useManifest()
  const focus = useFocus()
  const navigate = useNavigate()
  const [layer, setLayer] = useState<HeatLayer | null>(null)
  const entry = manifest.kind === 'loaded' ? manifest.manifest.provinces[province] : undefined
  const heat = useFile<Heat>(entry?.heat)
  const details = useFile<Details>(entry?.details)
  const detailsRef = useRef(details)
  useEffect(() => {
    detailsRef.current = details
  }, [details])

  useEffect(() => {
    if (!map) return
    let current: HeatLayer | null = null
    let cancelled = false
    // The drawing code comes with MapLibre's chunk, already loaded whenever a map is on screen
    import('./heatLayer').then(({ createHeatLayer }) => {
      if (cancelled) return
      current = createHeatLayer(map, {
        onSelect: (slug) => navigate(`/s/${slug}`),
        tooltip: (slug) => {
          const d = detailsRef.current?.stretches[slug]
          return d && `${d.name} · ${formatNumber(d.points)} pts · ${formatNumber(d.people)} people`
        },
      })
      setLayer(current)
    })
    return () => {
      cancelled = true
      current?.remove()
      setLayer(null)
    }
  }, [map, navigate])

  const bands = manifest.kind === 'loaded' ? manifest.manifest.bands : NO_BANDS
  const selected = focus?.province === province ? focus.slug : null
  const road = focus?.province === province ? focus.road?.join() : undefined
  useEffect(() => {
    layer?.update(heat ?? null, bands, selected, road ? road.split(',') : [])
  }, [layer, heat, bands, selected, road])

  return null
}
