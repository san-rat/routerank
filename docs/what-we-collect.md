# What RouteRank collects

The source for the privacy policy and the Privacy & data page (W31, W32 in Phase 7). Keep it in step with the
code: anything new that is stored or sent goes here first.

## Stored

| What | Where | Why | Kept |
|---|---|---|---|
| Google's account ID (`sub`) and email | `app_user` | One account per voter; signing in again finds the same account | Until the account is deleted |
| When the account was made, when its votes start counting | `app_user` | The 24-hour delay for new accounts | Until the account is deleted |
| Your three routes: their points, the roads they follow, slot, when saved, edited or removed | `route`, `route_waypoint`, `route_segment` | Your votes | Until deleted (removed routes are kept, marked removed, so admins can review them) |
| When each slot last changed | `slot_change` | The 24-hour slot cooldown | Until the account is deleted |
| A device signal: an HMAC (SHA-256, with a secret only the server has) of FingerprintJS's `visitorId` for your browser, and when it was first and last seen | `device_signal` | Spotting many accounts run from one device | 90 days after it was last seen |
| Fraud flags: which trigger fired (accounts on one device, a burst of new accounts with the same roads, the hidden form field), a group key, counts, and an admin's decision | `fraud_flag` | The admin review queue | Until the account is deleted |
| Whether your account is held or banned, and a trust score (the sum of your open flags' weights) | `app_user` | Held votes stay out of the rankings until an admin releases them | Until the account is deleted |
| Admin actions that concern your account or votes: what was done, when, by which admin, and their written reason | `audit_log` | Every admin action can be reviewed | Kept (append-only); it holds account IDs, not emails |
| Your signed-in session | `spring_session` | Staying signed in | 7 days |

Never stored: the raw `visitorId` or any of the browser details below, your IP address, your location ("Locate
me" only centres the map), your name or profile picture.

## Collected in the browser, never sent as they are

FingerprintJS (open source, version 5, MIT licence) reads these browser details and turns them into one
`visitorId`, which is sent with sign-in and saves; the server keeps only the HMAC of it. Its statistics request is
turned off (`monitoring: false`). The details it reads (FingerprintJS 5.2's source list): user-agent data, installed
fonts and font preferences, content blockers, an audio fingerprint, screen size, frame, colour depth, colour gamut and
HDR, a canvas fingerprint, OS and CPU, languages, device memory, CPU cores, time zone and date-time locale, whether
session storage, local storage, IndexedDB, openDatabase and cookies work, platform, plugins, touch support, vendor,
accessibility settings (inverted or forced colours, monochrome, contrast, reduced motion, reduced transparency),
maths precision, whether a PDF viewer is enabled, CPU architecture, Apple Pay and Private Click Measurement support,
audio latency, and basic WebGL details and extensions.

## Sent to other services

| To | What | When |
|---|---|---|
| Google | The sign-in itself (Google Identity Services) | Sign-in |
| Cloudflare Turnstile | Turnstile runs its invisible check in your browser; the API then sends Cloudflare the token and your IP address to verify it | Sign-in, saving or editing a route |
| Cloudflare (Pages, R2) | Ordinary web requests for the site, the map and the rankings files | Every visit |

The per-IP save limit is counted in the API's memory only and never written down.
