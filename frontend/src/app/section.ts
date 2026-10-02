import { useLocation } from 'react-router'

export type Section = 'map' | 'leaderboard' | 'routes'

/** Which main section (nav tab) an address belongs to */
export function useSection(): Section {
  const { pathname } = useLocation()
  if (pathname.startsWith('/leaderboard') || pathname === '/how-it-works') return 'leaderboard'
  if (pathname.startsWith('/me') || pathname.startsWith('/add') || pathname === '/privacy') return 'routes'
  return 'map'
}
