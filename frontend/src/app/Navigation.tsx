import { NavLink } from 'react-router'
import googleG from '../assets/icons/google-g.svg'
import logoMark from '../assets/icons/logo-mark.svg'
import mapActive from '../assets/icons/map-active.svg'
import mapTabActive from '../assets/icons/map-tab-active.svg'
import mapTab from '../assets/icons/map-tab.svg'
import map from '../assets/icons/map.svg'
import rankActive from '../assets/icons/rank-active.svg'
import rankTabActive from '../assets/icons/rank-tab-active.svg'
import rankTab from '../assets/icons/rank-tab.svg'
import rank from '../assets/icons/rank.svg'
import routeActive from '../assets/icons/route-active.svg'
import routeTabActive from '../assets/icons/route-tab-active.svg'
import routeTab from '../assets/icons/route-tab.svg'
import route from '../assets/icons/route.svg'
import { type Section, useSection } from './section'

const SECTIONS: { id: Section; label: string; to: string }[] = [
  { id: 'map', label: 'Map', to: '/map/western' },
  { id: 'leaderboard', label: 'Leaderboard', to: '/leaderboard' },
  { id: 'routes', label: 'My routes', to: '/me/routes' },
]

const NAV_ICONS = { map: [map, mapActive], leaderboard: [rank, rankActive], routes: [route, routeActive] }
const TAB_ICONS = { map: [mapTab, mapTabActive], leaderboard: [rankTab, rankTabActive], routes: [routeTab, routeTabActive] }

/** Mobile floating nav (Figma "Web nav") */
export function WebNav() {
  const section = useSection()
  return (
    <nav className="web-nav" aria-label="Main">
      {SECTIONS.map(({ id, label, to }) => {
        const active = id === section
        return (
          <NavLink key={id} to={to} className={active ? 'active' : undefined} aria-current={active ? 'page' : undefined}>
            <img src={NAV_ICONS[id][active ? 1 : 0]} alt="" width={18} height={18} />
            {label}
          </NavLink>
        )
      })}
    </nav>
  )
}

/** Desktop panel header (Figma "Desktop panel header"), signed-out state */
export function PanelHeader() {
  const section = useSection()
  return (
    <header>
      <div className="brand-row">
        <img src={logoMark} alt="" width={36} height={36} />
        <strong>RouteRank</strong>
        <NavLink to="/me" className="sign-in">
          <img src={googleG} alt="" width={18} height={18} />
          Sign in
        </NavLink>
      </div>
      <nav className="tabs" aria-label="Main">
        {SECTIONS.map(({ id, label, to }) => {
          const active = id === section
          return (
            <NavLink key={id} to={to} className={active ? 'active' : undefined} aria-current={active ? 'page' : undefined}>
              <img src={TAB_ICONS[id][active ? 1 : 0]} alt="" width={17} height={17} />
              {label}
            </NavLink>
          )
        })}
      </nav>
    </header>
  )
}
