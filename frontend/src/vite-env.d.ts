/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Full URL of the Sri Lanka PMTiles file */
  readonly VITE_TILES_URL: string
  /** Google OAuth client ID for sign-in; public, set per environment */
  readonly VITE_GOOGLE_CLIENT_ID?: string
  /** Where the published rankings are (the routerank-data bucket's public address); unset = no rankings yet */
  readonly VITE_DATA_URL?: string
  /** Cloudflare Turnstile widget's site key (public); unset = no bot check, as in local development */
  readonly VITE_TURNSTILE_SITE_KEY?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
