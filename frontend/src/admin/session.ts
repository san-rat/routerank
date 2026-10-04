import { createContext, use } from 'react'
import { ApiError } from '../api/client'

/** Lets any admin page send the admin back to the sign-in gate when the API asks for a fresh sign-in */
export const AdminSessionContext = createContext<{ expired: () => void }>({ expired: () => undefined })

/** Turns an admin API failure into a message, or back to the gate when the session needs a fresh sign-in */
export function useAdminError() {
  const { expired } = use(AdminSessionContext)
  return (e: unknown): string => {
    if (e instanceof ApiError && e.status === 401) {
      expired()
      return 'Sign in again to keep using the admin pages.'
    }
    if (e instanceof ApiError && e.status === 409) return 'Not done: it changed since you looked, or it isn’t allowed.'
    if (e instanceof ApiError && e.status === 422) return 'The route can’t be saved as drawn: check its problems.'
    return 'That didn’t work. Please try again.'
  }
}

export const when = (iso?: string | null) => (iso ? new Date(iso).toLocaleString('en-LK', { dateStyle: 'medium', timeStyle: 'short' }) : '—')
