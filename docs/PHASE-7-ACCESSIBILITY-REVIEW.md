# Phase 7 — Accessibility review

**Date:** 2026-09-13  
**Scope:** Staff SPA + public marketing pages.  
**Method:** Manual pass + Playwright landmark checks; `prefers-reduced-motion` already honored in CSS.

## Fixes applied in this phase

- Skip-to-content link on staff shell and public layout
- `main` landmark / `id="main-content"` for skip target
- Mobile nav `aria-expanded` / `aria-controls`
- Login errors exposed with `role="alert"`
- Decorative brand images remain `alt=""` (name is adjacent text)

## Remaining / deferred

| Item | Notes |
| --- | --- |
| Full axe CI on every route | Smoke covers landing + login; expand in CI later |
| Color contrast on muted text | Dark theme is intentional; verify WCAG AA on accent `#c8f542` against backgrounds before marketing launch |
| Data tables | Ensure header scope on dense tables when polishing Phase 8 |
| Focus trap in mobile drawer | Basic overlay; improve if keyboard users report issues |

## Manual checklist (operators)

1. Tab through login — focus visible (accent outline).
2. Open mobile menu — Escape / overlay click closes; focus not lost forever.
3. Toggle OS “reduce motion” — hero zoom / fade-up should collapse.
4. Screen reader: brand name announced once in header (not only as image).
