# RouteRank web

React + TypeScript + Vite, with MapLibre GL and a Protomaps base map. Node 20.19+ or 22.12+.

```bash
npm ci          # install
npm run dev     # dev server on http://localhost:5173
npm test        # Vitest
npm run lint    # oxlint
npm run build   # type-check and production build into dist/ (needs VITE_TILES_URL)
npm run api:types  # regenerate src/api/schema.d.ts from src/api/openapi.json
```

## The API

The site always calls its own `/api/...`; nothing calls the API's address directly, so cookies stay
first-party and no CORS is needed.

- **Production:** the Pages Function in `functions/api/[[path]].ts` forwards `/api/*` to the API on Azure
  with a shared secret (Pages settings `API_ORIGIN` and secret `PROXY_SECRET`; see `infra/azure/README.md`).
- **Locally:** Vite's dev and preview servers do the same, to `http://localhost:8080` by default
  (`API_PROXY_TARGET` and `ROUTERANK_AUTH_PROXY_SECRET` in `.env.development.local` change it).
- **Types:** `src/api/openapi.json` is the API's spec, kept current by the backend's `OpenApiSpecTests`;
  `npm run api:types` turns it into `src/api/schema.d.ts`, and CI fails if that file is out of date.
- **Sign-in:** Google Identity Services, with the client ID in `VITE_GOOGLE_CLIENT_ID` (public; the
  `VITE_GOOGLE_CLIENT_ID` repository variable for deploys). Google allows sign-in only from
  `http://localhost:5173` and `https://routerank.pages.dev`, so preview deployments can't sign in.

## The map file

`VITE_TILES_URL` is the full URL of the Sri Lanka PMTiles file. In development it is `/tiles/sri-lanka-YYYY-MM-DD.pmtiles` (`.env.development`), served from `../data/downloads` by the dev server; build the file first with `../data/basemap/build.sh`. In production it is the file's R2 address, set as the `VITE_TILES_URL` repository variable for the deploy workflow.

## Map rules

- **Labels** are English or hidden, never broken: MapLibre can't shape Sinhala or Tamil. See `src/map/labels.ts`.
- **Roads** stay neutral white and grey; teal is reserved for the vote heatmap and red for bus routes.
- **Cheap phones:** no rotation or tilt, the map stays within Sri Lanka, and MapLibre loads only when a map is on screen.
- **Credits:** OpenStreetMap and Protomaps, in the map's attribution control.
