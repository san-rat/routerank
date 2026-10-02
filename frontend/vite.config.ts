import { createReadStream, existsSync, statSync } from 'node:fs'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { resolve } from 'node:path'
import react from '@vitejs/plugin-react'
import { loadEnv, type Plugin } from 'vite'
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

// https://vite.dev/config/
export default defineConfig(({ command, mode }) => {
  if (command === 'build' && mode !== 'test' && !loadEnv(mode, process.cwd()).VITE_TILES_URL) {
    throw new Error('VITE_TILES_URL must be set for a production build (the PMTiles URL on R2)')
  }
  return {
    plugins: [react(), localTiles()],
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
    },
  }
})
