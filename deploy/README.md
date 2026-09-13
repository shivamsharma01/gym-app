# Deploy

See **[docs/PHASE-8.md](../docs/PHASE-8.md)** for the full stack, env template, and hardening checklist.

```bash
cp deploy/.env.example deploy/.env   # set APP_SECURITY_JWT_SECRET + DB passwords
docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile full up -d --build
```

MySQL only: `docker compose -f deploy/docker-compose.yml up -d mysql`
