import { useEffect } from 'react'
import { loadGoogle } from './google'
import { loadAccount, signedIn, signOut, useAccount } from './account'
import { SignInButton } from './SignInButton'

const dateTime = new Intl.DateTimeFormat('en-LK', { dateStyle: 'medium', timeStyle: 'short' })
const date = new Intl.DateTimeFormat('en-LK', { dateStyle: 'medium' })

/** /me: sign in with Google, or see the signed-in account */
export function AccountPage() {
  const state = useAccount()

  useEffect(() => {
    loadAccount({ verify: true })
  }, [])

  async function handleSignOut() {
    await signOut()
    loadGoogle().then((google) => google.id.disableAutoSelect(), () => {})
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
          <button type="button" className="secondary-button" onClick={handleSignOut}>
            Sign out
          </button>
        </>
      )}
    </main>
  )
}
