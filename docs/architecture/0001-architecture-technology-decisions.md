# ADR 0001 — Architecture & Technology Decisions

- **Status:** Accepted (Phase 0)
- **Date:** 2026-09-10
- **Context:** Smart Gym Management Platform integrating TrueFace3000 (Dahua NetSDK) access-control
  terminals. Decisions here bind Phases 1–8.

## Decision 1 — Backend is a modular monolith: Java 21 + Spring Boot 4.1
**Why:** Requirement mandates Java/Spring Boot and warns against unnecessary microservices (§52,
§58). SB 4.1.1 is the current stable, supports Java 21, and provides Security 7, Data JPA,
Actuator, WebSocket, and a managed dependency BOM. The DB is the business source of truth.

## Decision 2 — A dedicated Device Gateway is the only native-SDK component
**Why:** `dhnetsdk` needs a direct TCP/37777 session to each device, so a component must run on the
gym LAN (§4). Keeping all native calls there keeps Spring Boot free of native libraries (§4, §38).

## Decision 3 — Gateway is implemented in C# / .NET 10 (not Java/JNA)
**Why:** Master prompt §5C explicitly permits a .NET gateway when the SDK is better supported there.
The repo already contains a **complete, proven C# NetSDK binding** (`NetSDKCS`: 1.3k P/Invoke lines,
9.1k wrapper lines, 137k struct lines) and a demo that has talked to the real device. Re-binding
that surface into Java/JNA would duplicate an enormous, error-prone layer for no benefit. .NET 10 is
LTS and cross-platform (Linux). The main application stays Java.
**Consequence:** The .NET-Framework-4.0 binding is ported to .NET 10; `libdhnetsdk.so` must be
obtained for Linux (Windows DLLs are already present for near-term development).

## Decision 4 — Gateway→Backend link is an outbound persistent WSS
**Why:** The gym network should not expose inbound ports; NAT-friendliness is required (§4, §36).
The gateway initiates an authenticated WSS to the backend and both commands and events flow over it,
with a REST fallback for ingest. Idempotency keyed on `messageId`/`correlationId`; reconnect-safe
replay.

## Decision 5 — Attendance and Authorization are decoupled; sync uses a persistent outbox
**Why:** §6/§9 require separation and reliable, durable synchronization. Business change + sync
command are written in one transaction; a persistence-backed dispatcher retries with exponential
backoff + jitter, supports dead-letter, correlation IDs, reconciliation, and manual retry. No
fire-and-forget threads, no in-memory-only queues, no `Thread.sleep` retry loops (§58).

## Decision 6 — `DeviceAdapter` abstraction with Mock + Simulator + TrueFace implementations
**Why:** §38/§50 require a clean boundary, a mock, and a simulator so development proceeds without
hardware and integration points stay explicit. `MockDeviceAdapter` + a standalone simulator emulate
online/offline, events, denied/alarm, delayed/failed ACKs, duplicates, and gap+reconciliation.

## Decision 7 — Biometric data minimisation
**Why:** §12/§51 — templates stay on the device; the app stores only `MemberDeviceMapping` and
enrollment status. No raw face images/templates in DB, logs, or browser. An architecture note
documents exactly where biometric data lives and why.

## Decision 8 — Enrollment ships a guided on-device fallback until remote capture is verified
**Why:** `OperateAccessFaceService` returned `0x10030110` in the real session log. Per §12/§54 we do
not fake success. The remote-capture path is behind a `VERIFIED`-only switch; the default is the
guided "complete on device" workflow with post-hoc device verification.

## Decision 9 — Frontend: React 19.3 + TypeScript + Vite 8 + React Router 7 + TanStack Query
**Why:** §26 preferences and current stable releases. Server state via TanStack Query, minimal
global client state, Tailwind v4 + shadcn/Radix for accessible UI, Recharts, Lucide, date-fns,
Motion (reduced-motion aware). React 19.3 View Transitions are now stable.

## Decision 10 — MySQL 8.4 + Flyway (no Hibernate auto-DDL in production)
**Why:** §14 — deterministic, versioned migrations; explicit tenant scoping; UUID external ids;
optimistic locking; FKs and constraints; indexes matched to real query patterns.

## Rejected alternatives
- **Java/JNA gateway** — rejected (Decision 3): duplicates a proven C# binding at high risk.
- **Cloud service calling devices directly** — impossible: SDK needs LAN TCP/37777.
- **iAS as a runtime dependency / second source of truth** — rejected (§7): legacy/optional only.
- **Aggressive device polling for events** — rejected (§58): push callbacks + reconciliation.
- **JWT in localStorage by default** — rejected (§58): httpOnly cookie / in-memory + rotation.

## Open items carried into later phases
1. Obtain `libdhnetsdk.so` (Linux) from Dahua/TimeWatch (blocks Linux Phase 4).
2. Re-verify face enrollment / remote capture on hardware; confirm error-code semantics.
3. Gateway authentication is a **per-gateway hashed token** issued at create time, with an optional
   deployment-wide shared token (`APP_GATEWAY_SHARED_TOKEN`). mTLS remains a future hardening option.
4. Confirm device offline-authorization behaviour to finalise connectivity-state UX.
