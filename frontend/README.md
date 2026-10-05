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

### Adding routes locally

Adding routes needs the API with a routing graph and the same road import in its database (see
`data/README.md`, "Routing graph"). To try the flow without a Google account, run the API with its
test-only stand-in for Google, from `backend/`, against the Compose database:

```bash
SERVER_PORT=8090 SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/routerank SPRING_DATASOURCE_USERNAME=routerank SPRING_DATASOURCE_PASSWORD=routerank SPRING_FLYWAY_LOCATIONS=classpath:db/migration ROUTERANK_ROUTING_GRAPH_LOCATION=../data/downloads/graph-2026-10-01 ROUTERANK_ROUTING_OSM_FILE= ROUTERANK_AUTH_PROXY_SECRET=local-dev-proxy-secret ROUTERANK_TURNSTILE_SECRET= ROUTERANK_TURNSTILE_HOSTNAMES= ./gradlew bootTestRun
```

Then, with `API_PROXY_TARGET=http://localhost:8090` in `.env.development.local`, sign in from the
browser console on http://localhost:5173:

```js
const { nonce } = await (await fetch('/api/auth/nonce')).json()
const credential = await (await fetch(`/api/auth/dev-token?sub=dev&nonce=${nonce}`)).text()
const xsrf = decodeURIComponent(document.cookie.match(/XSRF-TOKEN=([^;]+)/)[1])
await fetch('/api/auth/google', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrf }, body: JSON.stringify({ credential }) })
localStorage.setItem('routerank.signedIn', '1')
```

`/api/auth/dev-token` exists only in that test launcher (`backend/src/test`), never in the built API. The empty
`ROUTERANK_TURNSTILE_SECRET` turns the bot check off locally (the tests use a stand-in for Cloudflare instead);
`VITE_TURNSTILE_SITE_KEY` is unset in development for the same reason.

### Rankings locally

The map's heatmap, the stretch pages and the leaderboards read the files the scoring job publishes, never the
API. In production they are on R2 (`VITE_DATA_URL`, the `routerank-data` bucket's public address). Locally, add
`ROUTERANK_SCORING_PUBLISH_DIRECTORY=../data/downloads/rankings` to the API command above: the job then writes
the files there, and the dev server serves them at `/rankings` (`VITE_DATA_URL=/rankings` in `.env.development`).
The job runs at :00 and :30. To run it now, or to try the admin pages (`/admin`: review queue, bus routes, audit
log), make the dev account an admin in the Compose database, sign in again (admin pages need a sign-in from the
last 15 minutes) and use "Run scoring now":

```bash
docker compose exec db psql -U routerank -c "UPDATE app_user SET role = 'admin' WHERE google_sub = 'dev'"
```

New accounts' votes count only after 24 hours, as in production.

## The map file

`VITE_TILES_URL` is the full URL of the Sri Lanka PMTiles file. In development it is `/tiles/sri-lanka-YYYY-MM-DD.pmtiles` (`.env.development`), served from `../data/downloads` by the dev server; build the file first with `../data/basemap/build.sh`. In production it is the file's R2 address, set as the `VITE_TILES_URL` repository variable for the deploy workflow.

## Map rules

- **Labels** are English or hidden, never broken: MapLibre can't shape Sinhala or Tamil. See `src/map/labels.ts`.
- **Roads** stay neutral white and grey; teal is reserved for the vote heatmap and red for bus routes.
- **Cheap phones:** no rotation or tilt, the map stays within Sri Lanka, and MapLibre loads only when a map is on screen.
- **Credits:** OpenStreetMap and Protomaps, in the map's attribution control.
