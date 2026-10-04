import { lazy, type ReactNode, Suspense } from 'react'
import { Navigate, type RouteObject } from 'react-router'
import { AccountPage } from '../auth/AccountPage'
import { AppShell } from './AppShell'
import { MapPage, Placeholder, ProvinceMap } from './pages'

// The add-route steps and My routes are for signed-in users only, so most visitors never download them
const PickPointsPage = lazy(() => import('../routes/PickPointsPage').then((m) => ({ default: m.PickPointsPage })))
const RoutePreviewPage = lazy(() => import('../routes/RoutePreviewPage').then((m) => ({ default: m.RoutePreviewPage })))
const RankPage = lazy(() => import('../routes/RankPage').then((m) => ({ default: m.RankPage })))
const MyRoutesPage = lazy(() => import('../routes/MyRoutesPage').then((m) => ({ default: m.MyRoutesPage })))

const later = (page: ReactNode) => <Suspense fallback={<main className="page" aria-busy="true" />}>{page}</Suspense>

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
      { path: '/add', element: later(<PickPointsPage />) },
      { path: '/add/route', element: later(<RoutePreviewPage />) },
      { path: '/add/rank', element: later(<RankPage />) },
      { path: '/me/routes', element: later(<MyRoutesPage />) },
      { path: '/me', element: <AccountPage /> },
      { path: '/me/privacy', element: <Placeholder title="Privacy & data" phase={7} /> },
      { path: '/privacy', element: <Placeholder title="Privacy policy" phase={7} /> },
      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
]
