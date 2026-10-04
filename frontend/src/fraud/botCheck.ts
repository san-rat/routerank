// What sign-in and saves send besides the user's own input: a Cloudflare Turnstile token (an invisible bot
// check) and the device signal. Both are best effort here; the API decides what to do without them.

interface TurnstileOptions {
  sitekey: string
  action: string
  execution: 'render' | 'execute'
  appearance: 'always' | 'execute' | 'interaction-only'
  callback: (token: string) => void
  'error-callback': () => void
}

interface TurnstileApi {
  render(container: HTMLElement, options: TurnstileOptions): string
  execute(container: HTMLElement): void
  remove(widgetId: string): void
}

declare global {
  interface Window {
    turnstile?: TurnstileApi
  }
}

const SCRIPT = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'

export const turnstileSiteKey: string | undefined = import.meta.env.VITE_TURNSTILE_SITE_KEY || undefined

let loading: Promise<TurnstileApi> | undefined

function loadTurnstile(): Promise<TurnstileApi> {
  loading ??= new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = SCRIPT
    script.async = true
    script.onload = () => (window.turnstile ? resolve(window.turnstile) : reject(new Error('Turnstile did not load')))
    script.onerror = () => {
      loading = undefined
      reject(new Error('Turnstile did not load'))
    }
    document.head.append(script)
  })
  return loading
}

/**
 * A fresh Turnstile token for this action ("signin" or "save"); each token works once. Invisible to people:
 * Cloudflare shows a check only when it can't tell. Undefined when the site has no Turnstile key (local development).
 */
export async function turnstileToken(action: 'signin' | 'save'): Promise<string | undefined> {
  if (!turnstileSiteKey) return undefined
  const turnstile = await loadTurnstile()
  const container = document.createElement('div')
  container.className = 'turnstile'
  document.body.append(container)
  try {
    return await new Promise<string>((resolve, reject) => {
      const id = turnstile.render(container, {
        sitekey: turnstileSiteKey!,
        action,
        execution: 'execute',
        appearance: 'interaction-only',
        callback: (token) => {
          turnstile.remove(id)
          resolve(token)
        },
        'error-callback': () => {
          turnstile.remove(id)
          reject(new Error('Turnstile failed'))
        },
      })
      turnstile.execute(container)
    })
  } finally {
    container.remove()
  }
}

let visitor: Promise<string | undefined> | undefined

/**
 * FingerprintJS's visitorId for this browser (the API keeps only a keyed hash of it), with its statistics request
 * turned off. Loaded only when needed; undefined if it fails.
 */
export function deviceSignal(): Promise<string | undefined> {
  visitor ??= import('@fingerprintjs/fingerprintjs')
    .then((fp) => fp.default.load({ monitoring: false }))
    .then((agent) => agent.get())
    .then((result) => result.visitorId)
    .catch(() => undefined)
  return visitor
}
