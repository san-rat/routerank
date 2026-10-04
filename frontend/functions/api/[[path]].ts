// Cloudflare Pages Function: forwards /api/* to the RouteRank API on Azure, so the site, its cookies
// and the API share one origin. Set in the Pages project (production): API_ORIGIN, e.g.
// https://routerank-api.centralindia.cloudapp.azure.com, and the secret PROXY_SECRET, which the API
// requires on every request. Map tiles never come through here.

interface Env {
  API_ORIGIN?: string
  PROXY_SECRET?: string
}

interface Context {
  request: Request
  env: Env
}

export async function onRequest({ request, env }: Context): Promise<Response> {
  if (!env.API_ORIGIN || !env.PROXY_SECRET) {
    return new Response('The API is not configured.', { status: 503 })
  }
  const url = new URL(request.url)
  const target = new URL(url.pathname + url.search, env.API_ORIGIN)

  // Cookies, Origin and the CSRF header pass through unchanged
  const headers = new Headers(request.headers)
  headers.delete('Host')
  headers.set('X-RouteRank-Proxy-Secret', env.PROXY_SECRET)
  // The visitor's IP for rate limits; never trust an X-Forwarded-For the visitor sent
  headers.delete('X-Forwarded-For')
  const ip = request.headers.get('CF-Connecting-IP')
  if (ip) headers.set('X-Forwarded-For', ip)

  return fetch(target, {
    method: request.method,
    headers,
    body: request.method === 'GET' || request.method === 'HEAD' ? undefined : request.body,
    redirect: 'manual',
  })
}
