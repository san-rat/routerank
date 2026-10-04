# The API on Azure

The API runs in Docker on one small Azure VM, with Caddy in front for HTTPS and Azure Database for
PostgreSQL behind it. Nobody calls it directly: the site's Pages Function (`frontend/functions/api`)
forwards `/api/*` to it with a shared secret, and the API refuses requests without that secret.

```
browser ──► routerank.pages.dev/api/* ──► Pages Function ──► https://<name>.<region>.cloudapp.azure.com
                                         (+ proxy secret)      Caddy ──► api:8080 ──► PostgreSQL
```

## One-time setup

Use one region for everything: Central India or Southeast Asia, whichever your subscription allows.

1. **PostgreSQL:** create an Azure Database for PostgreSQL flexible server: Burstable B1MS, PostgreSQL 18,
   public access. In its server parameters, add `POSTGIS` to `azure.extensions` (Flyway's first migration
   runs `CREATE EXTENSION postgis`). Create a database named `routerank`.
2. **VM:** create an Ubuntu 24.04 VM (B1s or B2ats v2) with SSH key sign-in. Allow inbound ports 22, 80
   and 443. Under the public IP's configuration, set a DNS name label; the VM's address is then
   `<label>.<region>.cloudapp.azure.com`.
3. **Database firewall:** allow the VM's public IP on the PostgreSQL server.
4. **On the VM:** install Docker Engine with the Compose plugin, add swap (the VM has about 1 GB of RAM),
   then create `~/routerank/.env`:

   ```bash
   API_HOST=<label>.<region>.cloudapp.azure.com
   SPRING_DATASOURCE_URL=jdbc:postgresql://<server>.postgres.database.azure.com:5432/routerank?sslmode=require
   SPRING_DATASOURCE_USERNAME=<admin user>
   SPRING_DATASOURCE_PASSWORD=<admin password>
   ROUTERANK_AUTH_GOOGLE_CLIENT_ID=<client id>.apps.googleusercontent.com
   ROUTERANK_AUTH_PROXY_SECRET=<output of: openssl rand -hex 32>
   ```

   Keep it private: `chmod 600 ~/routerank/.env`.

   The deploy also copies `graph.env` (which routing graph to load) and downloads that graph to
   `~/routerank/graphs`. Run compose by hand with both files:
   `docker compose --env-file .env --env-file graph.env up -d`.
5. **Deploy key:** make a key pair just for GitHub Actions (`ssh-keygen -t ed25519 -f routerank-deploy`),
   add the public half to `~/.ssh/authorized_keys` on the VM, then in the GitHub repository set:
   - secret `AZURE_VM_SSH_KEY`: the private key
   - secret `AZURE_VM_KNOWN_HOSTS`: the output of `ssh-keyscan <label>.<region>.cloudapp.azure.com`
   - variable `AZURE_VM_USER`: the VM's admin user
   - variable `AZURE_VM_HOST`: `<label>.<region>.cloudapp.azure.com` (the deploy is skipped until this is set)
6. **Pages:** in the Cloudflare Pages project's production settings, set the variable `API_ORIGIN` to
   `https://<label>.<region>.cloudapp.azure.com` and the secret `PROXY_SECRET` to the same value as
   `ROUTERANK_AUTH_PROXY_SECRET`.
7. **Rankings on R2 (Phase 5):** the scoring job publishes the heatmap and leaderboards to their own bucket.
   - In Cloudflare, create the bucket `routerank-data`, turn on its public r2.dev address, and set its CORS
     rules: `npx wrangler r2 bucket cors set routerank-data --file data/rankings/cors.json`.
   - Under R2, "Manage API tokens", create a token with **Object Read & Write** on `routerank-data` only.
   - Add to `~/routerank/.env` (then `chmod 600` again and restart compose):

     ```bash
     ROUTERANK_SCORING_PUBLISH_ENDPOINT=https://<account id>.r2.cloudflarestorage.com
     ROUTERANK_SCORING_PUBLISH_BUCKET=routerank-data
     ROUTERANK_SCORING_PUBLISH_ACCESS_KEY_ID=<token's Access Key ID>
     ROUTERANK_SCORING_PUBLISH_SECRET_ACCESS_KEY=<token's Secret Access Key>
     ```

   - Set the repository variable `VITE_DATA_URL` to the bucket's public address
     (`https://pub-<id>.r2.dev`) so the site reads the rankings from it.

   Without these the job still scores every 30 minutes (My routes shows busiest stretches) but publishes
   nothing, and the log says so. It stays inside R2's free tier: files are named by content hash and only
   new ones are uploaded, and it stops uploading at 800,000 writes in a month (`publish_usage`).

8. **Anti-fraud and admin (Phase 6):**
   - In Cloudflare, under Turnstile, add a widget for `routerank.pages.dev` (and `localhost` only if you
     want to try it locally), mode **Invisible**. Set its site key as the repository variable
     `VITE_TURNSTILE_SITE_KEY` (public), and add its secret key to `~/routerank/.env`, with a new random key
     for device signals (then `chmod 600` again and restart compose):

     ```bash
     ROUTERANK_TURNSTILE_SECRET=<the widget's secret key>
     ROUTERANK_FRAUD_DEVICE_HMAC_KEY=<output of: openssl rand -hex 32>
     ```

     Set both at the same time as the site key reaches the site: with the secret set and no site key, every
     sign-in and save is refused. Never change the HMAC key afterwards, or every device looks new.
   - Make yourself an admin, by hand in the database (the role is never set through the API; a trigger writes
     every change to the audit log): sign in on the site once, then over the usual temporary firewall rule
     run `UPDATE app_user SET role = 'admin' WHERE email = '<your Google email>';`. `ROUTERANK_ADMIN_EMAILS`
     is no longer read and can be removed from `.env`. The admin pages are at `/admin`, linked from nowhere.

## Deploys

`.github/workflows/deploy-api.yml` runs on every push to main that changes `backend/` or this folder.
It builds the image, pushes it to GitHub Container Registry as `latest` and the commit SHA, copies
`compose.yml`, `Caddyfile` and `graph.env` here to `~/routerank`, downloads the routing graph named in
`graph.env` if it isn't there yet (checking its SHA-256), restarts the containers, and checks
`/actuator/health` (the one path that doesn't need the proxy secret). Flyway migrates the database
when the API starts. The roads themselves are loaded separately (data/README.md, "Loading production"), and the
API refuses to start if the graph and the newest `import_run` come from different extracts.

To roll back, set the image in `compose.yml` to an earlier commit SHA and push.
