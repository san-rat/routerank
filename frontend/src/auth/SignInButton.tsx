import { useEffect, useRef, useState } from 'react'
import { api, ApiError, type Me } from '../api/client'
import { deviceSignal, turnstileToken } from '../fraud/botCheck'
import { googleClientId, loadGoogle } from './google'

/**
 * "Continue with Google". Each attempt uses a fresh nonce from the API, and sends an invisible Turnstile check and
 * the device signal with Google's token.
 */
export function SignInButton({ onSignedIn }: { onSignedIn: (me: Me) => void }) {
  const button = useRef<HTMLDivElement>(null)
  const [attempt, setAttempt] = useState(0)
  const [error, setError] = useState<string>()

  useEffect(() => {
    if (!googleClientId) return
    let cancelled = false
    Promise.all([api.nonce(), loadGoogle()])
      .then(([{ nonce }, google]) => {
        if (cancelled || !button.current) return
        google.id.initialize({
          client_id: googleClientId!,
          nonce,
          ux_mode: 'popup',
          auto_select: false,
          cancel_on_tap_outside: true,
          callback: ({ credential }) => {
            Promise.all([turnstileToken('signin'), deviceSignal()])
              .then(([turnstile, device]) => api.signIn(credential, turnstile, device))
              .then(onSignedIn, (e: unknown) => {
                setError(
                  e instanceof ApiError && e.code?.startsWith('bot_check')
                    ? "Couldn't check that you're not a bot. Please try again."
                    : 'Sign-in failed. Please try again.',
                )
                setAttempt((n) => n + 1) // the nonce is used up; get a new one
              })
          },
        })
        google.id.renderButton(button.current, {
          type: 'standard',
          theme: 'outline',
          size: 'large',
          text: 'continue_with',
          shape: 'pill',
          width: 280,
        })
      })
      .catch(() => !cancelled && setError('Sign-in is unavailable right now. Please try again later.'))
    return () => {
      cancelled = true
    }
  }, [attempt, onSignedIn])

  if (!googleClientId) return <p role="alert">Sign-in is not configured on this site.</p>
  return (
    <>
      <div ref={button} className="google-button" />
      {error && <p role="alert">{error}</p>}
    </>
  )
}
