# Phase 9 — Multi-gym white-label & configurable public site

**Status:** Delivered  
**Depends on:** Phases 0–6  
**Still remaining after this:** None on the numbered roadmap ([PHASE-8.md](PHASE-8.md) delivered). Phase 7 quality reviews are in [PHASE-7.md](PHASE-7.md).

## Bootstrap model

- Flyway seeds **roles/permissions only** (no tenants, no gym users).
- Dev profile seeds a single platform account: `superadmin` / `ChangeMe123!`.
- **SUPER_ADMIN** enrolls each gym (tenant + profile + first `GYM_OWNER`) from `/app/platform/gyms`.
- That owner then creates further staff (`/app/users`) and members (`/app/members`) in the UI.
- No demo gym (`downtown-fitness` or similar) is created by SQL or the seeder.

## What shipped

### Super-admin gym enrollment
- `GET/POST /api/v1/platform/tenants` — `ROLE_SUPER_ADMIN` only
- Creates `Tenant` + default `GymProfile` + first `GYM_OWNER` account
- Staff UI: **Gyms** (`/app/platform/gyms`)

### Public tenant resolution
- Header `X-Gym-Slug` (preferred for local `/g/{slug}` paths)
- Query `?slug=`
- Subdomain of `app.public.base-domain` in production
- Optional `app.public.tenant-slug` fallback (empty by default)

Frontend: `/` is a platform landing (or redirects if `VITE_DEFAULT_GYM_SLUG` is set). Marketing pages under `/g/:gymSlug/...`.

### Branding
- `gym_profile` brand fields (Flyway `V6__phase9_white_label.sql`)
- Settings edits brand; empty image URLs use `frontend/public/brand/defaults/`
- Public chrome + staff `AppShell` use display name / logo

### Homepage
- Hero → training → facilities → plans → contact CTA with restrained CSS motion

## Config

| Variable | Purpose |
| --- | --- |
| `APP_PUBLIC_TENANT_SLUG` | Optional fallback slug (leave empty) |
| `APP_PUBLIC_BASE_DOMAIN` | Apex domain for `{slug}.domain` |
| `VITE_DEFAULT_GYM_SLUG` | Optional redirect target for `/` |

## Out of scope (later)
- Custom domains, CDN uploads, email invites, Phase 8 production Docker/DNS.
