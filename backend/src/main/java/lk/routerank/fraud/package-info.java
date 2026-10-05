/**
 * Anti-fraud: Turnstile, the honeypot, device signals, per-device and per-IP save limits, the hourly fraud scan and
 * holds. Triggers alone decide holds; a hold is never turned into a ban automatically.
 */
@org.springframework.modulith.ApplicationModule
package lk.routerank.fraud;
