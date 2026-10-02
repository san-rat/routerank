/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Full URL of the Sri Lanka PMTiles file */
  readonly VITE_TILES_URL: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
