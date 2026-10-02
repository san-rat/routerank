import { Navigate, useParams } from 'react-router'
import { provinceBySlug } from '../map/provinces'
import { DESKTOP, useMediaQuery } from './useMediaQuery'

/** A page that arrives in a later phase */
export function Placeholder({ title, phase }: { title: string; phase: number }) {
  return (
    <main className="page">
      <h1>{title}</h1>
      <p>Coming in Phase {phase}.</p>
    </main>
  )
}

/** Map routes: the map itself lives in AppShell; on desktop the panel shows this */
export function MapPage({ title, phase }: { title?: string; phase?: number }) {
  const desktop = useMediaQuery(DESKTOP)
  if (!desktop) return null
  return title && phase ? <Placeholder title={title} phase={phase} /> : (
    <main className="page">
      <p>Rankings for this province arrive in Phase 5.</p>
    </main>
  )
}

/** /map/:province — unknown provinces go to Western */
export function ProvinceMap() {
  const { province } = useParams()
  return provinceBySlug(province) ? <MapPage /> : <Navigate to="/map/western" replace />
}
