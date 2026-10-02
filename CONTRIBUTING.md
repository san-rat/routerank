# Contributing to RouteRank

Thanks for helping. RouteRank is a public-good, non-profit project with one maintainer, so small, focused pull requests are the easiest to review.

## Getting started

1. Read the [README](README.md) and run the stack with `docker compose up --build` from `infra/`.
2. Pick an issue labelled **good first issue**, or open an issue first for anything larger.
3. Branch from `main`, make your change, and open a pull request.

## Rules

- **Tests:** every rule in the spec gets an automated test. Backend: `./gradlew build`. Frontend: `npm run lint && npm test && npm run build`.
- **CI must pass** and one review is required before merge. Only the `all-green` check is required; jobs for untouched parts are skipped.
- **Commit messages** are scoped: `feat(api): …`, `fix(web): …`, `chore(infra): …`, `docs: …`.
- **No secrets** in the repo, ever. Production keys live only in the host's environment.
- **One feature, one pull request**, including both backend and frontend changes when needed.

## Help that needs no code

- Fixing road classes and one-way tags in OpenStreetMap.
- Mapping metro bus routes.
- Translations (later versions).

By contributing, you agree that your contributions are licensed under the [AGPL-3.0](LICENSE) and that you follow the [Code of Conduct](CODE_OF_CONDUCT.md).
