# RouteRank

RouteRank is a mobile-first website where the public votes on where new metro bus routes in Sri Lanka should run. Each vote adds points to the road stretches it covers, and rankings, updated every 30 minutes, show which roads people most want served.

**Status:** Phase 0 (setup). Nothing user-facing works yet.

## Repository layout

```
routerank/
├─ backend/            Spring Boot API (Java 21, Gradle)
├─ frontend/           React + TypeScript + Vite
├─ data/               OSM import + segmenting scripts (Phase 1)
├─ infra/              Docker Compose, deploy scripts
├─ docs/               Links to the spec, architecture and build plan
└─ .github/workflows/  CI
```

## Run everything locally

Needs Docker Desktop (or Docker Engine with Compose v2).

```bash
cd infra
docker compose up --build
```

| Service | URL |
| --- | --- |
| Web | http://localhost:8081 |
| API health | http://localhost:8080/actuator/health |
| PostGIS | localhost:5432 (database, user and password: `routerank`) |

## Work on one part

- Backend: `cd backend && ./gradlew build` (Gradle downloads JDK 21 if you don't have it).
- Frontend: `cd frontend && npm ci && npm run dev` (Node 20.19+ or 22.12+). See [frontend/README.md](frontend/README.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md).

## Licence

[GNU Affero General Public License v3.0](LICENSE). Anyone running a modified copy of RouteRank must share their changes.

Map data © OpenStreetMap contributors.
