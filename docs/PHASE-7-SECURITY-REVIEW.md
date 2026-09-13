# Phase 7 — Security review

**Date:** 2026-09-13  
**Scope:** Backend JWT/RBAC/tenancy, public APIs, staff SPA, gateway token model.  
**Method:** Code review + existing integration tests (auth lockout, refresh rotation, tenant isolation, Phase 9 enroll authz).

## Strengths

- Stateless JWT access + rotating refresh tokens; reuse of an old refresh is rejected.
- Method-level `@PreAuthorize` on controllers; permissions seeded in Flyway.
- `TenantGuard` fails closed (cross-tenant → NOT_FOUND).
- Platform `SUPER_ADMIN` is `tenant_id = null`; gym enroll is `ROLE_SUPER_ADMIN` only.
- Public site resolves gym via `X-Gym-Slug` / subdomain — no demo tenant invented when unset.
- CORS allow-list; CSRF disabled intentionally for bearer-token API (documented in `SecurityConfig`).
- Audit log for auth and sensitive domain actions.
- Gateway auth uses per-gateway tokens (not user JWT); biometrics stay on device.

## Risks / findings

| ID | Severity | Finding | Mitigation status |
| --- | --- | --- | --- |
| S1 | High (ops) | Default JWT secret in `application.yml` if env unset | Must set `APP_SECURITY_JWT_SECRET` in any non-local deploy (Phase 8) |
| S2 | Medium | SUPER_ADMIN has all permissions but no gym “act-as” UI — accidental broad API use if scripts pass null tenant wrong | Documented; prefer enroll-only ops until Phase 8 support tooling |
| S3 | Medium | Notification delivery is mock — no real PII egress yet, but templates store member-facing text | Keep mock until vendor adapter; never log face images |
| S4 | Low | Public enquiry endpoint is unauthenticated (by design) | Rate limiting / CAPTCHA deferred to Phase 8 |
| S5 | Low | Forgot-password is a stub | Honest UX; no token email yet |
| S6 | Info | Dev password `ChangeMe123!` for `superadmin` | Dev profile only; change before shared environments |

## Verified by tests

- Unauthenticated access → 401
- Missing permission → 403
- Refresh rotation + reuse rejection
- Account lockout after repeated failures
- Tenant isolation IT
- Gym owner cannot call platform enroll; SUPER_ADMIN can

## Recommendations before production (Phase 8)

1. Force strong JWT secret and disable default.
2. Add rate limits on `/api/v1/auth/login` and `/api/v1/public/enquiries`.
3. HTTPS termination + secure cookie policy only if cookies are introduced (prefer stay bearer).
4. External dependency scan (OWASP dependency-check / GitHub Dependabot).
