# Phase 7 — Testing, security, performance, accessibility

**Status:** Delivered  
**Depends on:** Phases 0–6, 9  
**Still remaining:** Phase 8 (Docker/deploy/production hardening)

## Deliverables

| Area | What shipped |
| --- | --- |
| Backend tests | Existing ITs retained; added `Phase7SecurityIT` (public slug isolation, cross-tenant member 404, X-Frame-Options, platform list) |
| Gateway tests | Existing unit suite (Mock adapter, dispatcher, envelopes) remains the offline gate |
| Frontend E2E | Playwright smoke under `frontend/e2e/` (landing, login a11y landmarks, optional live API via `E2E_API=1`) |
| Security review | [PHASE-7-SECURITY-REVIEW.md](PHASE-7-SECURITY-REVIEW.md) |
| Performance review | [PHASE-7-PERFORMANCE-REVIEW.md](PHASE-7-PERFORMANCE-REVIEW.md) |
| Accessibility review | [PHASE-7-ACCESSIBILITY-REVIEW.md](PHASE-7-ACCESSIBILITY-REVIEW.md) + skip-link / landmark / menu ARIA fixes |

## How to run

```bash
# Backend integration tests (Testcontainers MySQL)
cd backend && mvn test

# Gateway unit tests
cd gateway && dotnet test

# Frontend build + lint
cd frontend && npm run build && npm run lint

# E2E (install browsers once: npx playwright install)
cd frontend && npm run test:e2e
```

Optional live E2E against a running API:

```bash
E2E_API=1 npm run test:e2e
```

## Explicit gaps left for Phase 8 / later

- Full CI matrix (browsers × OS) and production load tests
- Penetration test by a third party
- Real notification delivery and payment-provider hardening (product, not Phase 7)
- Docker multi-service compose (Phase 8)
