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

1. **PostgreSQL:** create an Azure Database for PostgreSQL flexible server: Burstable B1MS, PostgreSQL 16,
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
5. **Deploy key:** make a key pair just for GitHub Actions (`ssh-keygen -t ed25519 -f routerank-deploy`),
   add the public half to `~/.ssh/authorized_keys` on the VM, then in the GitHub repository set:
   - secret `AZURE_VM_SSH_KEY`: the private key
   - secret `AZURE_VM_KNOWN_HOSTS`: the output of `ssh-keyscan <label>.<region>.cloudapp.azure.com`
   - variable `AZURE_VM_USER`: the VM's admin user
   - variable `AZURE_VM_HOST`: `<label>.<region>.cloudapp.azure.com` (the deploy is skipped until this is set)
6. **Pages:** in the Cloudflare Pages project's production settings, set the variable `API_ORIGIN` to
   `https://<label>.<region>.cloudapp.azure.com` and the secret `PROXY_SECRET` to the same value as
   `ROUTERANK_AUTH_PROXY_SECRET`.

## Deploys

`.github/workflows/deploy-api.yml` runs on every push to main that changes `backend/` or this folder.
It builds the image, pushes it to GitHub Container Registry as `latest` and the commit SHA, copies
`compose.yml` and `Caddyfile` here to `~/routerank`, restarts the containers, and checks
`/actuator/health` (the one path that doesn't need the proxy secret). Flyway migrates the database
when the API starts.

To roll back, set the image in `compose.yml` to an earlier commit SHA and push.
