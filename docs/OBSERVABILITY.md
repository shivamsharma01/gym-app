# Observability, monitoring, and rate limiting

Practical production setup for a **single Spring Boot service + MySQL + one VPS**. No Prometheus/Grafana/Loki/Redis stack.

## Architecture

```
Hostinger VPS dashboard
  │  CPU / RAM / disk / network / VPS uptime
  ▼
Internet → nginx (SPA :80)
  │  proxies /api, /live, /gateway, /actuator/health only
  ▼
Spring Boot (:8080)
  ├── Actuator (health public; metrics/info/threaddump = SUPER_ADMIN)
  ├── Micrometer JVM + HTTP + Hikari metrics
  ├── Structured Logback (MDC: requestId, tenantId, userId)
  ├── Correlation / X-Request-Id
  └── In-memory rate limiting (public IP + authenticated user/tenant)
```

## Actuator decision: same port + Spring Security

We keep Actuator on the **application port** (not a separate management port).

| Endpoint | Access | Why |
|----------|--------|-----|
| `/actuator/health` (+ `/liveness`, `/readiness`) | Public | Compose healthcheck + external uptime monitors |
| `/actuator/info` | `ROLE_SUPER_ADMIN` | Build/app metadata — platform SPA **Operations console** |
| `/actuator/metrics` (+ metric names) | `ROLE_SUPER_ADMIN` | JVM, HTTP, Hikari — platform SPA |
| `/actuator/threaddump` | `ROLE_SUPER_ADMIN` | Thread diagnosis — platform SPA |
| `/actuator/env`, `configprops`, `heapdump`, `loggers`, `shutdown` | **denyAll + not exposed** | Secrets / heavy dumps |

`show-details` / `show-components` = `when_authorized` — anonymous health returns status only.

**nginx** proxies `/actuator/` (Authorization forwarded). SUPER_ADMIN uses the React **Actuator** page at `/app/platform/actuator`. Gym staff and anonymous callers still get 401/403 from Spring for non-health endpoints.

## Metrics (Micrometer via Actuator)

Available under `/actuator/metrics` (SUPER_ADMIN), including:

- HTTP: request counts, status, latency (`http.server.requests`)
- JVM: memory, threads, GC, classes, CPU
- HikariCP: connections active/idle/pending, max, acquire time

No custom business meters unless a clear need appears later.

## Logging

- Pattern includes `requestId`, `tenantId`, `userId` (from authenticated context — never from client-supplied tenant headers).
- Access logger `gym.access`: method, path, status, durationMs.
- Prod: console + rolling file under `LOG_PATH` (default `/var/log/gym`), 50MB parts, 14 days, 1GB cap, gzip.
- Dev: console only.

Headers: `X-Correlation-Id` and `X-Request-Id` (same value). Unsafe incoming IDs are rejected and replaced.

## Rate limiting

Abstraction: `RateLimitStore` (in-memory today; Redis-capable later).

| Category | Key | Default / minute | When |
|----------|-----|------------------|------|
| Login | IP | 20 | `POST /api/v1/auth/login` |
| Refresh | IP | 30 | `POST /api/v1/auth/refresh` |
| Public enquiry | IP | 10 | `POST /api/v1/public/**/enquiries` |
| Password change | user | 10 | `POST /api/v1/auth/me/password` |
| Reports | tenant+user | 30 | `/api/v1/reports/**` |
| Authenticated API | tenant+user | 300 | other `/api/**` |

Exceeding → **429** ProblemDetail (`code=RATE_LIMITED`) + `Retry-After`.

Account lockout (failed passwords) remains separate (`app.security.lockout`).

## Health

- DB connectivity via DataSource health indicator.
- Probes enabled for liveness/readiness.
- Unhealthy when MySQL is down (readiness fails).

## Hostinger vs Spring

| Concern | Where |
|---------|--------|
| VPS CPU/RAM/disk/network/availability | Hostinger panel |
| App up/down, DB, JVM, HTTP rates, pool | Spring Actuator + logs |
| External “site down” | UptimeRobot / Better Stack / similar → `GET https://your.domain/actuator/health` |

## Suggested alerts (actionable)

| Condition | Threshold | Severity | Action |
|-----------|-----------|----------|--------|
| External health fails | 2–3 consecutive | P1 | Check VPS, compose, nginx, MySQL |
| Health `DOWN` / DB | any | P1 | MySQL logs, disk, credentials |
| HTTP 5xx spike | sustained >2% or burst | P2 | Logs by `requestId`, recent deploy |
| Hikari pending / exhausted | pending >0 sustained or active≈max | P2 | Slow queries, pool size, leaks |
| Disk | <15% free | P1 | Log retention, MySQL data, backups |
| Hostinger CPU/RAM | high >15–30m | P2 | JFR if app-related |
| Backup failure | job failed | P1 | Restore path / storage |

## Profiling (JFR) — Actuator is not a profiler

On the VPS (JDK 21), when investigating CPU/allocation:

```bash
# Find PID
docker top gym-backend   # or jps / pgrep java

# 60s recording (example; adjust PID / container)
docker exec gym-backend jcmd 1 JFR.start name=gym duration=60s filename=/tmp/gym.jfr
# wait, then copy out:
docker cp gym-backend:/tmp/gym.jfr ./gym.jfr
```

Open `gym.jfr` in JDK Mission Control. Do **not** expose JFR or heap dumps over HTTP.

## Incident sequence

1. Hostinger: VPS up?
2. `curl -fsS https://domain/actuator/health` (and `/healthz` on nginx)
3. Readiness / DB component (as SUPER_ADMIN if details needed)
4. MySQL container healthy?
5. Error rate / latency via `/actuator/metrics` (SUPER_ADMIN) or access logs
6. Grep logs by `requestId=` / `tenantId=`
7. Hikari metrics if DB-ish slowness
8. JFR if CPU/allocation mystery remains

## Manual VPS notes

- Ensure `LOG_PATH` writable (compose volume `gym-backend-logs`).
- Do not publish backend `:8080` publicly if nginx is the edge; prefer bind to localhost or private network.
- Point an external monitor at `/actuator/health` every 1–5 minutes.
- After first prod SUPER_ADMIN bootstrap, clear `APP_BOOTSTRAP_SUPERADMIN_PASSWORD`.
