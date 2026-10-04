// Google Identity Services in callback mode: Google hands the ID token to the page, which posts it to
// the API together with the nonce the API issued. No redirect, so no g_csrf_token cookie.

interface CredentialResponse {
  credential: string
}

interface IdConfiguration {
  client_id: string
  callback: (response: CredentialResponse) => void
  nonce: string
  ux_mode: 'popup'
  auto_select: boolean
  cancel_on_tap_outside: boolean
}

interface GsiButtonConfiguration {
  type: 'standard'
  theme: 'outline' | 'filled_blue'
  size: 'large'
  text: 'continue_with' | 'signin_with'
  shape: 'pill'
  width: number
}

export interface GoogleAccounts {
  id: {
    initialize(config: IdConfiguration): void
    renderButton(parent: HTMLElement, options: GsiButtonConfiguration): void
    disableAutoSelect(): void
  }
}

declare global {
  interface Window {
    google?: { accounts: GoogleAccounts }
  }
}

const SCRIPT = 'https://accounts.google.com/gsi/client'

let loading: Promise<GoogleAccounts> | undefined

/** Loads Google's sign-in script once, only when a sign-in button is on screen */
export function loadGoogle(): Promise<GoogleAccounts> {
  loading ??= new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = SCRIPT
    script.async = true
    script.onload = () => (window.google ? resolve(window.google.accounts) : reject(new Error('Google sign-in did not load')))
    script.onerror = () => {
      loading = undefined
      reject(new Error('Google sign-in did not load'))
    }
    document.head.append(script)
  })
  return loading
}

export const googleClientId: string | undefined = import.meta.env.VITE_GOOGLE_CLIENT_ID || undefined
