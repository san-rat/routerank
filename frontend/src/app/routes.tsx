import { Navigate, type RouteObject } from 'react-router'
import { AccountPage } from '../auth/AccountPage'
import { AppShell } from './AppShell'
import { MapPage, Placeholder, ProvinceMap } from './pages'

// Every address from the Spec's page table; later phases replace the placeholders
export const routes: RouteObject[] = [
  {
    element: <AppShell />,
    children: [
      { path: '/', element: <MapPage /> },
      { path: '/map/:province', element: <ProvinceMap /> },
      { path: '/s/:stretch', element: <MapPage title="Ranked stretch" phase={5} /> },
      { path: '/bus/:number', element: <MapPage title="Bus route" phase={6} /> },
      { path: '/r/:route', element: <MapPage title="Shared route" phase={7} /> },
      { path: '/leaderboard', element: <Placeholder title="Leaderboard" phase={5} /> },
      { path: '/leaderboard/:province', element: <Placeholder title="Province leaderboard" phase={5} /> },
      { path: '/how-it-works', element: <Placeholder title="How ranking works" phase={5} /> },
      { path: '/add', element: <Placeholder title="Add a route" phase={4} /> },
      { path: '/add/route', element: <Placeholder title="Add a route" phase={4} /> },
      { path: '/add/rank', element: <Placeholder title="Rank your route" phase={4} /> },
      { path: '/me/routes', element: <Placeholder title="My routes" phase={4} /> },
      { path: '/me', element: <AccountPage /> },
      { path: '/me/privacy', element: <Placeholder title="Privacy & data" phase={7} /> },
      { path: '/privacy', element: <Placeholder title="Privacy policy" phase={7} /> },
      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
]
