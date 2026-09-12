# Phase 9 — Multi-gym white-label & configurable public site

**Status:** Delivered  
**Depends on:** Phases 0–6  
**Still remaining after this:** Phase 7 (quality/E2E/security review), Phase 8 (Docker/deploy/hardening)

## What shipped

### Super-admin gym enrollment
- `GET/POST /api/v1/platform/tenants` — `ROLE_SUPER_ADMIN` only
- Creates `Tenant` + default `GymProfile` + first `GYM_OWNER` account
- Staff UI: **Gyms** (`/app/platform/gyms`) for platform users

### Public tenant resolution
- Header `X-Gym-Slug` (preferred for local `/g/{slug}` paths)
- Query `?slug=`
- Subdomain of `app.public.base-domain` in production (e.g. `h13gym.example.com`)
- Fallback: `app.public.tenant-slug` (default `downtown-fitness`)

Frontend routes: `/` → `/g/{VITE_DEFAULT_GYM_SLUG}`; all marketing pages under `/g/:gymSlug/...`.

### Branding
- `gym_profile` extended (Flyway `V6__phase9_white_label.sql`): display name, logo/hero/training/facilities image URLs, section copy
- Staff **Settings** edits brand; empty image URLs use bundled defaults in `frontend/public/brand/defaults/`
- Public chrome + staff `AppShell` use display name / logo — no hardcoded “True Gym”

### Homepage
- Sequential sections: full-bleed hero → training → facilities → plans → contact CTA
- CSS motion (`fade-up`, `hero-zoom`) with `prefers-reduced-motion` respected

## Config

| Variable | Purpose |
| --- | --- |
| `APP_PUBLIC_TENANT_SLUG` | Fallback slug |
| `APP_PUBLIC_BASE_DOMAIN` | Apex domain for `{slug}.domain` resolution |
| `VITE_DEFAULT_GYM_SLUG` | Frontend redirect target for `/` |

## Out of scope (later)
- Custom domains per gym, CDN uploads, email invites, Phase 7 E2E suite, Phase 8 production Docker/DNS.
