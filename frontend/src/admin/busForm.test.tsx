import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { BusRouteView, Drawing, LatLon } from '../api/client'
import type { RouteDrawing, RouteEditing } from '../map/routeLayer'
import { BusRouteFormPage } from './BusRoutesAdmin'

// The map isn't drawn in tests: keep what the form hands the route layer
const layer = vi.hoisted(() => ({ drawing: undefined as RouteDrawing | undefined, editing: undefined as RouteEditing | undefined }))
vi.mock('../routes/useRouteLayer', () => ({
  useRouteLayer: (_map: unknown, drawing: RouteDrawing, editing: RouteEditing) => {
    layer.drawing = drawing
    layer.editing = editing
  },
}))

const fort = { lat: 6.934, lon: 79.843 }
const kollupitiya = { lat: 6.915, lon: 79.849 }
const townHall = { lat: 6.917, lon: 79.864 }

const stored: BusRouteView = {
  id: 3, number: '101', startName: 'Fort', endName: 'Kollupitiya', start: fort, end: kollupitiya, waypoints: [],
  backWaypoints: [], out: [], back: [], lengthOutM: 2_600, lengthBackM: 2_700, towns: [],
  createdAt: '2026-10-06T10:00:00Z', updatedAt: '2026-10-06T10:00:00Z', retiredAt: null as unknown as string, extensions: 0,
}

function drawing(backWaypoints: LatLon[]): Drawing {
  const via = backWaypoints.length > 0
  return {
    points: [fort, kollupitiya], backWaypoints,
    out: [[fort.lon, fort.lat], [kollupitiya.lon, kollupitiya.lat]],
    back: via
      ? [[kollupitiya.lon, kollupitiya.lat], [townHall.lon, townHall.lat], [fort.lon, fort.lat]]
      : [[kollupitiya.lon, kollupitiya.lat], [fort.lon, fort.lat]],
    lengthOutM: 2_600, lengthBackM: via ? 4_900 : 2_700, backVia: via ? ['Dharmapala Mawatha'] : [],
    startName: 'Fort', endName: 'Kollupitiya', towns: [], problems: [],
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('drawing a bus route', () => {
  it('shapes the way back with waypoints of its own, and resets it to the fastest', async () => {
    const draws: { start?: LatLon; end?: LatLon; backWaypoints?: LatLon[] }[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/admin/bus-routes') return new Response(JSON.stringify([stored]))
      if (url === '/api/admin/bus-routes/draw') {
        const body = JSON.parse(String(init!.body))
        draws.push(body)
        return new Response(JSON.stringify(drawing(body.backWaypoints ?? [])))
      }
      return new Response(null, { status: 404 })
    }))
    render(
      <MemoryRouter initialEntries={['/admin/buses/3']}>
        <Routes>
          <Route path="/admin/buses/:id" element={<BusRouteFormPage />} />
        </Routes>
      </MemoryRouter>,
    )
    expect(await screen.findByText(/there 2.6 km \(0 of 25 waypoints\), back 2.7 km \(the fastest\)/)).toBeInTheDocument()
    expect(layer.drawing?.start).toEqual(fort)

    // On the way back, the bus starts at Kollupitiya: the pins swap and the way back is the line to shape
    await userEvent.click(screen.getByRole('button', { name: 'Way back' }))
    expect(screen.getByRole('button', { name: 'Way back' })).toHaveAttribute('aria-pressed', 'true')
    expect(layer.drawing?.start).toEqual(kollupitiya)
    expect(layer.drawing?.end).toEqual(fort)
    expect(layer.drawing?.out).toEqual(drawing([]).back)
    expect(layer.drawing?.backLeaves).toEqual([drawing([]).out])

    act(() => layer.editing!.onLineClick!(townHall))
    await waitFor(() => expect(draws.at(-1)?.backWaypoints).toEqual([townHall]))
    expect(await screen.findByText(/back 4.9 km \(1 of 25 waypoints, uses Dharmapala Mawatha\)/)).toBeInTheDocument()
    expect(layer.drawing?.waypoints).toEqual([townHall])
    // Dragging the start pin on the way back moves the bus's end
    act(() => layer.editing!.onMovePoint!('start', { lat: 6.914, lon: 79.849 }))
    await waitFor(() => expect(draws.at(-1)).toMatchObject({ start: fort, end: { lat: 6.914, lon: 79.849 } }))

    // The way there keeps its own (no) waypoints
    await userEvent.click(screen.getByRole('button', { name: 'Way there' }))
    expect(layer.drawing?.waypoints).toEqual([])

    await userEvent.click(screen.getByRole('button', { name: 'Reset way back' }))
    await waitFor(() => expect(draws.at(-1)?.backWaypoints).toEqual([]))
    expect(screen.queryByRole('button', { name: 'Reset way back' })).not.toBeInTheDocument()
  })
})
