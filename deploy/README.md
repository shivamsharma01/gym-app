# Deploy

See **[docs/PHASE-8.md](../docs/PHASE-8.md)** and **[GO-LIVE.md](GO-LIVE.md)** for stack, bootstrap, TLS, and gateway cutover.

```bash
cp deploy/.env.example deploy/.env
# set APP_SECURITY_JWT_SECRET; for first boot also APP_BOOTSTRAP_SUPERADMIN_PASSWORD
docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile full up -d --build
```

MySQL only: `docker compose -f deploy/docker-compose.yml up -d mysql`
