# RouteRank web

React + TypeScript + Vite, with MapLibre GL and a Protomaps base map. Node 20.19+ or 22.12+.

```bash
npm ci          # install
npm run dev     # dev server on http://localhost:5173
npm test        # Vitest
npm run lint    # oxlint
npm run build   # type-check and production build into dist/ (needs VITE_TILES_URL)
```

## The map file

`VITE_TILES_URL` is the full URL of the Sri Lanka PMTiles file. In development it is `/tiles/sri-lanka-YYYY-MM-DD.pmtiles` (`.env.development`), served from `../data/downloads` by the dev server; build the file first with `../data/basemap/build.sh`. In production it is the file's R2 address, set as the `VITE_TILES_URL` repository variable for the deploy workflow.

## Map rules

- **Labels** are English or hidden, never broken: MapLibre can't shape Sinhala or Tamil. See `src/map/labels.ts`.
- **Roads** stay neutral white and grey; teal is reserved for the vote heatmap and red for bus routes.
- **Cheap phones:** no rotation or tilt, the map stays within Sri Lanka, and MapLibre loads only when a map is on screen.
- **Credits:** OpenStreetMap and Protomaps, in the map's attribution control.
