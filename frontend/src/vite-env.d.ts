/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Full URL of the Sri Lanka PMTiles file */
  readonly VITE_TILES_URL: string
  /** Google OAuth client ID for sign-in; public, set per environment */
  readonly VITE_GOOGLE_CLIENT_ID?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
