# Roadmap history (phases 0–9)

The numbered phase markdown files were **removed** after the roadmap shipped. This index is
enough to know what each phase covered; implementation lives in the codebase.

| Phase | Theme |
|-------|--------|
| 0 | Repo / SDK assessment & architecture baseline |
| 1–2 | Core API, auth, tenancy, members, memberships |
| 3–4 | Device gateway protocol & Windows TrueFace agent |
| 5 | Staff React SPA |
| 6 | Notifications / announcements (mock providers) |
| 7 | Tests + security / performance / accessibility reviews |
| 8 | Docker, deploy hardening, go-live ops |
| 9 | Multi-gym white-label & public site |

**Still open:** [product.md](product.md). **Deploy today:** [deploy/README.md](../deploy/README.md).

To recover an old phase doc: `git log --all -- docs/PHASE-*.md` then `git show <commit>:docs/…`.
