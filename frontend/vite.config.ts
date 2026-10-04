import { createReadStream, existsSync, statSync } from 'node:fs'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { resolve } from 'node:path'
import react from '@vitejs/plugin-react'
import { type HtmlTagDescriptor, loadEnv, type Plugin } from 'vite'
import { defineConfig } from 'vitest/config'

// Local only (dev server and `vite preview`): serve data/downloads/*.pmtiles at
// /tiles with HTTP range requests, which PMTiles needs. In production the file is on R2.
function localTiles(): Plugin {
  const dir = resolve(__dirname, '../data/downloads')

  function serve(req: IncomingMessage, res: ServerResponse, next: () => void) {
    const file = resolve(dir, decodeURIComponent((req.url ?? '').split('?')[0].replace(/^\//, '')))
    if (!file.startsWith(dir) || !file.endsWith('.pmtiles') || !existsSync(file)) return next()
    const size = statSync(file).size
    const range = /bytes=(\d+)-(\d*)/.exec(req.headers.range ?? '')
    res.setHeader('Accept-Ranges', 'bytes')
    res.setHeader('Content-Type', 'application/octet-stream')
    if (!range) {
      res.setHeader('Content-Length', size)
      createReadStream(file).pipe(res)
      return
    }
    const start = Number(range[1])
    const end = range[2] ? Math.min(Number(range[2]), size - 1) : size - 1
    res.statusCode = 206
    res.setHeader('Content-Range', `bytes ${start}-${end}/${size}`)
    res.setHeader('Content-Length', end - start + 1)
    createReadStream(file, { start, end }).pipe(res)
  }

  return {
    name: 'local-tiles',
    configureServer: (server) => void server.middlewares.use('/tiles', serve),
    configurePreviewServer: (server) => void server.middlewares.use('/tiles', serve),
  }
}

// Production only: shorten the map's request chain (page → app JS → map JS → tiles).
// - preconnect to the tile and glyph hosts so their TLS handshakes overlap the JS downloads;
// - on pages that show a map (same rule as AppShell: always on desktop, map routes on mobile),
//   start fetching the MapView chunk alongside the app JS. Other pages still skip MapLibre.
function fasterMap(tilesUrl: string | undefined): Plugin {
  return {
    name: 'faster-map',
    apply: 'build',
    transformIndexHtml(_html, ctx) {
      const tags: HtmlTagDescriptor[] = []
      for (const origin of new Set([tilesUrl && /^https?:/.test(tilesUrl) ? new URL(tilesUrl).origin : undefined, 'https://protomaps.github.io'])) {
        if (origin) tags.push({ tag: 'link', attrs: { rel: 'preconnect', href: origin, crossorigin: '' }, injectTo: 'head' })
      }
      const mapChunk = Object.values(ctx.bundle ?? {}).find((c) => c.type === 'chunk' && c.name === 'MapView')
      if (mapChunk) {
        tags.push({
          tag: 'script',
          children:
            `if(matchMedia('(min-width: 900px)').matches||!/^\\/(leaderboard|me|add)|^\\/(how-it-works|privacy)$/.test(location.pathname)){` +
            `var l=document.createElement('link');l.rel='modulepreload';l.href='/${mapChunk.fileName}';document.head.appendChild(l)}`,
          injectTo: 'head',
        })
      }
      return tags
    },
  }
}

// https://vite.dev/config/
export default defineConfig(({ command, mode }) => {
  if (command === 'build' && mode !== 'test' && !loadEnv(mode, process.cwd()).VITE_TILES_URL) {
    throw new Error('VITE_TILES_URL must be set for a production build (the PMTiles URL on R2)')
  }
  // Local stand-in for the Pages Function in functions/api: forwards /api to the API with the proxy
  // secret, so the browser only ever talks to the site's own origin (same as production)
  const env = loadEnv(mode, process.cwd(), '')
  const apiProxy = {
    '/api': {
      target: env.API_PROXY_TARGET || 'http://localhost:8080',
      headers: { 'X-RouteRank-Proxy-Secret': env.ROUTERANK_AUTH_PROXY_SECRET || 'local-dev-proxy-secret' },
    },
  }
  return {
    plugins: [react(), localTiles(), fasterMap(loadEnv(mode, process.cwd()).VITE_TILES_URL)],
    server: { proxy: apiProxy },
    preview: { proxy: apiProxy },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
    },
  }
})
