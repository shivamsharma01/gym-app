# Deploy

Provider-agnostic production stack: **MySQL + Spring Boot + Nginx** on a Linux VPS.
React is hosted separately (Cloudflare Pages). See **[VPS.md](VPS.md)** for the full guide.

**VPS (API only):**

```bash
cp deploy/.env.example deploy/.env
# set MYSQL_* passwords, APP_SECURITY_JWT_SECRET, keep SPRING_PROFILES_ACTIVE=prod
chmod +x deploy/scripts/*.sh
./deploy/scripts/up.sh --build
```

**Optional local SPA container:** `--profile spa` (uses `nginx.spa-local.conf`, same-origin proxy).

**HTTPS:** Cloudflare public TLS preferred; optional origin TLS via [`nginx.api.https.conf.example`](nginx.api.https.conf.example).

**Backup:** `./deploy/scripts/backup-mysql.sh /path/to/backup.sql`

Cutover / gateway checklist: **[GO-LIVE.md](GO-LIVE.md)**.
