import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, type Me } from '../api/client'
import { loadGoogle } from './google'
import { SignInButton } from './SignInButton'

type State = { kind: 'loading' } | { kind: 'signed-out' } | { kind: 'error' } | { kind: 'signed-in'; me: Me }

const dateTime = new Intl.DateTimeFormat('en-LK', { dateStyle: 'medium', timeStyle: 'short' })
const date = new Intl.DateTimeFormat('en-LK', { dateStyle: 'medium' })

/** /me: sign in with Google, or see the signed-in account */
export function AccountPage() {
  const [state, setState] = useState<State>({ kind: 'loading' })

  useEffect(() => {
    let cancelled = false
    api.me().then(
      (me) => !cancelled && setState({ kind: 'signed-in', me }),
      (error) => !cancelled && setState({ kind: error instanceof ApiError && error.status === 401 ? 'signed-out' : 'error' }),
    )
    return () => {
      cancelled = true
    }
  }, [])

  const signedIn = useCallback((me: Me) => setState({ kind: 'signed-in', me }), [])

  async function signOut() {
    await api.signOut()
    loadGoogle().then((google) => google.id.disableAutoSelect(), () => {})
    setState({ kind: 'signed-out' })
  }

  return (
    <main className="page account">
      {state.kind === 'loading' && <p>Loading…</p>}
      {state.kind === 'error' && <p role="alert">Couldn't reach RouteRank. Please try again later.</p>}
      {state.kind === 'signed-out' && (
        <>
          <h1>Sign in</h1>
          <p>Sign in with Google to add your routes. RouteRank keeps only your Google account ID and email.</p>
          <SignInButton onSignedIn={signedIn} />
        </>
      )}
      {state.kind === 'signed-in' && (
        <>
          <h1>Account</h1>
          <dl className="account-details">
            <dt>Email</dt>
            <dd>{state.me.email}</dd>
            <dt>Joined</dt>
            <dd>{date.format(new Date(state.me.createdAt))}</dd>
            <dt>Your votes count from</dt>
            <dd>{dateTime.format(new Date(state.me.liveAt))}</dd>
          </dl>
          <button type="button" className="secondary-button" onClick={signOut}>
            Sign out
          </button>
        </>
      )}
    </main>
  )
}
