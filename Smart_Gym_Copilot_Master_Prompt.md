# MASTER COPILOT PROMPT — SMART GYM MANAGEMENT + TRUEFACE3000 INTEGRATION

## ROLE

You are acting simultaneously as:

1. Principal Software Architect
2. Senior Java/Spring Boot Backend Engineer
3. Senior React/TypeScript Frontend Engineer
4. Device/IoT Integration Engineer
5. Application Security Engineer
6. Database Architect
7. Product Designer / UX Engineer
8. QA/Test Automation Engineer
9. DevOps/Deployment Engineer
10. Code Reviewer

Your job is NOT to merely propose an architecture or generate a partial demo. Build a production-oriented, maintainable, extensible Smart Gym Management Platform from the requirements below.

Work as an autonomous senior engineer. Inspect the repository before making changes. Create a clear implementation plan, then implement it in the repository. Do not stop at pseudocode, TODO lists, mock screens, or architecture diagrams.

If an external dependency, SDK, native library, device API, or exact SDK function signature is unavailable, NEVER invent the API. Create a clean abstraction, a mock/simulator implementation, diagnostics, and an explicit integration boundary so the real SDK can be plugged in later from the official documentation. Clearly mark only the exact unresolved integration points.

---

# 1. PRODUCT VISION

Build a modern Smart Gym Management Platform that replaces the poor day-to-day software experience around the existing biometric/access-control hardware.

The product should feel like a premium SaaS product, not an old attendance-management application.

Primary goals:

- Make member management extremely easy.
- Make membership validity the business source of truth.
- Keep TrueFace3000 responsible for biometric recognition.
- Do NOT implement facial-recognition algorithms in the application.
- Synchronize authorization from the gym application to the physical devices.
- Capture entry/exit events reliably.
- Provide a beautiful responsive admin UI usable on desktop, tablet, and mobile browsers.
- Provide live device/event visibility.
- Provide useful management analytics.
- Provide notifications and communication workflows.
- Provide strong RBAC and auditability.
- Be multi-tenant-ready so the same platform can eventually serve multiple gyms.
- Be production-ready rather than a throwaway proof of concept.

The final experience should make a gym owner think:
“This is a serious business system that is safer, clearer and more useful than the old attendance software.”

---

# 2. IMPORTANT HARDWARE CONTEXT

Hardware model:

TrueFace3000 by TimeWatch.

The provided hardware documentation describes:

- Standalone access-control device
- Embedded Linux
- 2MP dual-lens camera
- Face, password, MIFARE card and QR verification
- Mask detection
- Liveness detection
- Visitor QR
- 5,000 users
- 5,000 faces
- 5,000 passwords
- 5,000 cards
- 5,000 QR codes
- Up to 300,000 records
- Face recognition distance: 0.3m–1.5m
- Face recognition height: 1.1m–2.0m
- Claimed recognition accuracy: 99.9%
- Claimed recognition time: 0.2s
- TCP/IP and Wi-Fi
- Web configuration
- SDK/API
- Network update
- Remote verification
- Restricted/trusted list
- Real-time monitoring
- Auto registration
- Anti-passback
- Tamper alarm
- Duress alarm
- Door status detection
- Remote lock control

There are two devices in the gym:

- Device A = Entrance
- Device B = Exit

Treat them as independently configurable devices with a logical role/direction rather than hard-coding “device 1” and “device 2”.

Important: device capabilities must be treated as configuration and integration capabilities, not as assumptions that every undocumented operation is guaranteed.

---

# 3. TRUEFACE SDK CONTEXT

The available discussion and test evidence indicates that the TrueFace SDK is based around the Dahua NetSDK family and native libraries.

Known evidence includes:

- SDK login succeeded against a device on TCP port 37777.
- User query operations succeeded.
- User add/modify/query operations succeeded.
- Card query completed.
- Real-time event listening was started.
- Face capture command was sent and acknowledged.
- The SDK exposes functions/classes around:
  - connection/session management
  - user management
  - face management
  - fingerprint/card management
  - access-control operations
  - real-time access events
  - alarm callbacks
  - attendance records
  - device configuration
  - device discovery
  - auto-registration
  - reconnect handling

Known examples from the existing integration investigation include:

- CLIENT_InitEx
- CLIENT_Cleanup
- CLIENT_LoginWithHighLevelSecurity
- CLIENT_LoginEx2
- CLIENT_Logout
- CLIENT_SetAutoReconnect
- CLIENT_SetConnectTime
- CLIENT_GetConnectionStatus
- CLIENT_Attendance_AddUser
- CLIENT_Attendance_DelUser
- CLIENT_Attendance_ModifyUser
- CLIENT_Attendance_GetUser
- CLIENT_Attendance_FindUser
- CLIENT_StartFindUserInfo
- CLIENT_DoFindUserInfo
- CLIENT_OperateAccessUserService
- CLIENT_Attendance_InsertFingerByUserID
- CLIENT_Attendance_RemoveFingerByUserID
- CLIENT_Attendance_GetFingerByUserID
- CLIENT_StartFindCardInfo
- CLIENT_DoFindCardInfo
- CLIENT_OperateAccessCardService
- CLIENT_DetectFace
- CLIENT_OperateFaceRecognitionDB
- CLIENT_OperateFaceRecognitionGroup
- CLIENT_StartFindFaceRecognition
- CLIENT_DoFindFaceRecognition
- CLIENT_FaceInfoOpreate
- CLIENT_OperateAccessFaceService
- NETClient.AccessControlCaptureCmd
- CLIENT_RealLoadPictureEx
- CLIENT_FindNextRecord
- CLIENT_QueryRecordCount
- CLIENT_QueryDeviceTime
- CLIENT_SetupDeviceTime
- CLIENT_RebootDev
- CLIENT_StartUpgradeEx
- CLIENT_ListenServer
- CLIENT_StopListenServer
- CLIENT_StartSearchDevices
- CLIENT_SearchDevicesByIPs
- CLIENT_ModifyDevice
- CLIENT_InitDevAccount

Known event types include access events and alarms such as:

- ALARM_ACCESS_CTL_EVENT
- ALARM_ACCESS_CTL_NOT_CLOSE
- ALARM_ACCESS_CTL_BREAK_IN
- ALARM_ACCESS_CTL_REPEAT_ENTER
- ALARM_ACCESS_CTL_DURESS
- ALARM_ACCESS_CTL_MALICIOUS
- ALARM_CHASSISINTRUDED
- ALARM_ALARM_EX2

Known attendance states include:

- SIGNIN
- SIGNOUT
- GOOUT
- GOOUT_AND_RETRUN
- WORK_OVERTIME_SIGNIN
- WORK_OVERTIME_SIGNOUT

The device exposes enough documented capability to make direct integration promising, but the exact API behavior for the specific firmware/device must be validated against the actual SDK documentation and hardware.

DO NOT fake support for an SDK function merely because a similarly named method exists in a sample.

---

# 4. CRITICAL INTEGRATION ARCHITECTURE

Do NOT put native device SDK calls directly into ordinary Spring REST controllers or the core business services.

Use a dedicated Device Gateway / Device Integration Service.

Recommended logical architecture:

                    ┌─────────────────────────────┐
                    │        React Web App        │
                    │ Admin + Owner + Staff + Web │
                    └──────────────┬──────────────┘
                                   │ HTTPS / WSS
                                   ▼
                    ┌─────────────────────────────┐
                    │       Spring Boot API       │
                    │  Auth / Members / Billing   │
                    │  Attendance / Reports       │
                    │  Notifications / Audit      │
                    └──────────────┬──────────────┘
                                   │
                         Secure outbound channel
                                   │
                                   ▼
                    ┌─────────────────────────────┐
                    │       Device Gateway        │
                    │ local to the gym/device LAN │
                    │ SDK adapter + sync engine    │
                    └──────────────┬──────────────┘
                                   │ TCP/IP / official SDK
                    ┌──────────────┴──────────────┐
                    ▼                             ▼
             TrueFace3000 A                 TrueFace3000 B
               ENTRANCE                        EXIT

Database remains the business source of truth.

Device Gateway is the only component that knows the native TrueFace SDK.

Spring Boot must not depend on native SDK libraries.

The gateway should establish an outbound persistent secure connection to the cloud/backend when possible. This avoids requiring the gym router to expose inbound ports to the internet and works better with NAT.

For local deployment, gateway and backend may run on the same LAN/machine.

---

# 5. DEVICE GATEWAY LANGUAGE DECISION

The main backend MUST be Java + Spring Boot.

For the native SDK gateway, use this decision process:

A. First inspect the supplied official SDK package and documentation.

B. If the SDK provides reliable Java/JNA-compatible native APIs:
   - implement the gateway in Java
   - use JNA/JNI only behind a very small adapter
   - keep all native interaction isolated from business logic.

C. If the official SDK is clearly better supported through C#/.NET/native examples:
   - it is acceptable to implement the gateway as a small dedicated .NET service/process.
   - DO NOT force Java merely for ideological consistency.
   - the main application remains Java/Spring Boot.

D. Regardless of language:
   - expose a clean internal DeviceAdapter interface
   - provide a MockDeviceAdapter
   - provide a TrueFaceNativeAdapter boundary
   - keep SDK-specific structs, callbacks, pointers, handles and error codes out of the core domain
   - provide device diagnostics and health reporting.

---

# 6. ATTENDANCE VS AUTHORIZATION — KEEP THEM SEPARATE

This is a core architectural rule.

FLOW A: ATTENDANCE

TrueFace device recognizes a member
        ↓
device creates access event
        ↓
Device Gateway receives event
        ↓
Gateway normalizes event
        ↓
Spring Boot processes/stores attendance event
        ↓
React live dashboard receives event

FLOW B: AUTHORIZATION

Member/membership changes
        ↓
Spring Boot evaluates access policy
        ↓
Device synchronization command created
        ↓
Device Gateway receives command
        ↓
Gateway updates TrueFace device
        ↓
device acknowledges
        ↓
gateway records synchronization status
        ↓
application shows device sync state

These two flows must not be tightly coupled.

---

# 7. SOURCE OF TRUTH

The application's database must be the authoritative business source of truth for:

- members
- membership
- membership validity
- payment records
- access status
- gym policies
- device mappings
- roles
- permissions
- audit logs
- notifications
- application-level attendance history

iAS is NOT the new source of truth.

Treat iAS as legacy/optional compatibility only.

Do not design a system where both iAS and this application independently modify device authorization.

Provide an optional future integration boundary for legacy iAS migration, but do not make iAS a runtime dependency.

---

# 8. MEMBERSHIP → DEVICE AUTHORIZATION

Implement the business concept:

membership status → access policy → device authorization

Examples:

ACTIVE
→ member allowed according to access schedule.

EXPIRED
→ access disabled/restricted.

FROZEN
→ access suspended.

PAYMENT_OVERDUE
→ access restricted according to gym policy.

CANCELLED
→ access revoked.

NEW MEMBER
→ enrollment workflow + access authorization.

RENEWED
→ membership validity updated and device authorization synchronized.

Do not assume “changing a database row is enough”.
The device authorization must be synchronized too.

Model membership and device authorization separately so the system can show:

Business state:
ACTIVE

Device state:
SYNCHRONIZED / PENDING / FAILED / OFFLINE

Never silently pretend that a device update succeeded.

---

# 9. DEVICE SYNCHRONIZATION ENGINE

Implement a robust command/outbox-based synchronization architecture.

Example commands:

- CREATE_USER
- UPDATE_USER
- DISABLE_USER
- ENABLE_USER
- REMOVE_USER
- UPDATE_VALIDITY
- UPDATE_ACCESS_POLICY
- ENROLL_FACE
- DELETE_FACE
- SYNC_DEVICE_TIME
- OPEN_DOOR
- CLOSE_DOOR
- REFRESH_DEVICE_USERS
- RECONCILE_DEVICE
- CLEAR_DEVICE_LOGS (admin-only and strongly protected)

Each command must have:

- UUID command ID
- tenant ID
- device ID
- member ID if applicable
- command type
- payload
- created timestamp
- attempt count
- next retry timestamp
- state
- last error
- correlation ID
- acknowledged timestamp
- completed timestamp

States:

PENDING
DISPATCHED
ACKNOWLEDGED
SUCCEEDED
RETRYING
FAILED
DEAD_LETTER
CANCELLED

Use transactional creation of a business change + synchronization work item where appropriate.

Do not use fire-and-forget threads from REST controllers.

Use a reliable persistence-backed job/outbox mechanism.

Implement:

- retries with exponential backoff
- jitter
- maximum attempts
- dead-letter state
- idempotency
- correlation IDs
- structured logs
- reconciliation
- manual retry
- manual sync
- device resync
- visible sync status

---

# 10. ATTENDANCE SYNCHRONIZATION

Use a hybrid strategy:

1. Real-time events when the SDK/device provides them.
2. Reconciliation/query of device records after:
   - gateway restart
   - device reconnect
   - gateway reconnect
   - detected event gap
   - scheduled interval
   - manual admin request

Because the device can hold a large number of attendance/log records, do not rely solely on real-time callbacks.

Maintain a synchronization watermark per device.

When reconnecting:

- determine the last known synchronized point
- query appropriate device records
- normalize them
- deduplicate them
- persist missing events
- advance watermark
- expose synchronization health

Never duplicate attendance when a real-time event and reconciliation later report the same physical event.

Use a stable device event ID when the SDK provides one.

If not available, build a conservative deterministic event fingerprint from available fields such as:

device + user + timestamp + direction/method + record sequence/device record identifier

Do not claim perfect deduplication when the device API does not expose a stable identifier.

---

# 11. OFFLINE / CONNECTIVITY HANDLING

The application must NOT show a simplistic failure message such as:

“Internet is down, nobody can enter.”

The device is standalone and can perform local verification, but exact offline authorization behavior must be verified against the actual device/SDK.

Therefore the implementation must distinguish:

- DEVICE_ONLINE
- DEVICE_OFFLINE
- GATEWAY_ONLINE
- GATEWAY_OFFLINE
- BACKEND_REACHABLE
- LAST_SUCCESSFUL_SYNC
- PENDING_DEVICE_COMMANDS
- ATTENDANCE_RECONCILIATION_REQUIRED

Build the system to tolerate temporary communication failure.

When the internet/backend is unavailable:

- device should continue to operate according to its own configured local authorization capabilities
- gateway should reconnect automatically
- queued synchronization commands should be retried
- attendance records should be reconciled after recovery
- UI should transparently show stale/unsynced state

Do not fabricate an online authorization model that requires the server to answer every face scan unless the hardware/SDK actually supports that design.

---

# 12. ENROLLMENT WORKFLOW

Design a first-class member enrollment flow.

Desired business flow:

Admin/Staff
  ↓
Add Member
  ↓
name / phone / member details
  ↓
membership plan
  ↓
membership dates
  ↓
access policy
  ↓
create application member
  ↓
create device user mapping
  ↓
face enrollment
  ↓
TrueFace device performs capture/biometric processing
  ↓
application records device-user/credential mapping
  ↓
device authorization synchronized
  ↓
display “Ready for Access”

The application MUST NOT implement face-recognition algorithms.

The TrueFace device performs the biometric processing.

The application may implement the workflow that triggers/coordinates enrollment if the SDK allows it.

If the exact SDK supports remote capture:
- provide remote enrollment workflow.

If the exact SDK does not support the exact remote enrollment behavior:
- provide a guided “Complete enrollment on device” workflow
- record enrollment status
- verify device state afterward
- never fake success.

Face image/template storage:

Default to data minimization.

Prefer keeping biometric material on the device if the SDK makes that possible.

Do not store raw face images or biometric templates in the application database unless absolutely required and justified by the actual integration.

If an image must temporarily pass through the gateway:
- keep it in memory or short-lived protected storage
- enforce deletion/retention rules
- never log biometric payloads
- never include biometric data in API logs.

---

# 13. SECURITY REQUIREMENTS

Use Spring Security correctly.

Implement:

- authentication
- authorization
- RBAC
- tenant isolation
- endpoint protection
- method-level authorization
- secure password hashing
- session/token security
- refresh token rotation
- account lock/rate limiting for suspicious authentication attempts
- audit logging
- CORS configuration
- CSRF protection appropriate to the selected authentication model
- secure headers
- input validation
- output encoding
- mass-assignment protection
- least privilege
- secrets via environment variables/secrets manager
- no plaintext credentials in Git
- no device passwords in frontend code
- no SDK credentials in browser code.

Preferred roles:

SUPER_ADMIN
GYM_OWNER
GYM_ADMIN
STAFF
FRONT_DESK
REPORT_VIEWER

Example permission model:

MEMBER_VIEW
MEMBER_CREATE
MEMBER_UPDATE
MEMBER_DELETE
MEMBERSHIP_VIEW
MEMBERSHIP_CREATE
MEMBERSHIP_UPDATE
MEMBERSHIP_FREEZE
MEMBERSHIP_CANCEL
PAYMENT_VIEW
PAYMENT_CREATE
ATTENDANCE_VIEW
REPORT_VIEW
NOTIFICATION_SEND
DEVICE_VIEW
DEVICE_MANAGE
DEVICE_SYNC
DEVICE_REMOTE_CONTROL
SECURITY_ALERT_VIEW
USER_MANAGE
ROLE_MANAGE
AUDIT_VIEW
SETTINGS_MANAGE

Do not rely only on frontend route guards. Every protected backend operation must be authorized server-side.

High-risk physical operations such as remote door opening must require elevated permission and create an immutable audit entry.

Use optimistic UI only for non-critical interactions.

---

# 14. DATABASE

Use MySQL.

Use Spring Data JPA.

Use Flyway for schema migration.

Do not allow Hibernate auto-create/update in production.

Suggested core domain:

Tenant
GymProfile
AdminUser
Role
Permission
UserRole
Member
MembershipPlan
Membership
Payment
AccessPolicy
MemberDeviceMapping
Device
DeviceCredential
DeviceSyncCommand
DeviceSyncAttempt
DeviceSyncState
AttendanceEvent
AttendanceSyncCursor
SecurityEvent
NotificationTemplate
Notification
Announcement
AuditLog
Holiday
AccessSchedule
StaffNote
Enquiry
SystemSetting

Use:

- UUIDs for externally visible identifiers where appropriate
- internal numeric keys only where useful
- created_at
- updated_at
- created_by
- updated_by
- version columns for optimistic locking where needed
- soft deletion only where business/audit requirements justify it
- indexes based on actual query patterns
- unique constraints for tenant-scoped identifiers
- foreign keys
- check constraints where supported
- appropriate transaction boundaries.

Tenant isolation must be explicit.

Every tenant-owned entity must be scoped to a tenant.

Never load a member purely by member ID without checking tenant ownership.

Avoid N+1 queries.

Use projections/specifications/entity graphs/query methods appropriately.

---

# 15. BACKEND ARCHITECTURE

Use a clean modular architecture.

Suggested package structure:

com.example.gym
  config
  security
  common
  tenant
  auth
  member
  membership
  payment
  attendance
  device
  notification
  reporting
  audit
  dashboard

Within each bounded feature:

controller
service
domain
repository
dto
mapper
exception

Do NOT create giant “God services”.

Prefer:

- command/service layer
- domain services
- repositories
- strategy pattern where rules vary
- adapter pattern for SDKs
- factory pattern for device adapter selection
- outbox pattern for reliable synchronization
- state pattern where device/sync lifecycle benefits from it
- specification/query object pattern for complex searching
- observer/event-driven patterns where appropriate
- strategy pattern for notification channel selection
- facade where SDK complexity needs isolation

Do not force design patterns where plain code is clearer.

Use DTOs rather than exposing JPA entities directly through REST.

Use Bean Validation.

Centralized error handling with Problem Details / RFC 9457-style responses where supported.

Provide pagination, sorting and filtering for list APIs.

Return consistent response structures.

Generate OpenAPI documentation.

---

# 16. REST API AREAS

Implement clean versioned APIs, e.g.:

/api/v1/auth/*
/api/v1/me/*
/api/v1/members/*
/api/v1/memberships/*
/api/v1/plans/*
/api/v1/payments/*
/api/v1/attendance/*
/api/v1/devices/*
/api/v1/device-sync/*
/api/v1/notifications/*
/api/v1/reports/*
/api/v1/dashboard/*
/api/v1/users/*
/api/v1/roles/*
/api/v1/audit/*
/api/v1/settings/*
/api/v1/enquiries/*

Include:

- validation
- authorization
- pagination
- filtering
- sorting
- proper HTTP status codes
- idempotency where appropriate
- trace/correlation ID
- structured errors.

---

# 17. REAL-TIME UI

Use WebSocket or Server-Sent Events for admin live data.

Prefer WebSocket if bidirectional communication is useful.

The live dashboard should update without manual refresh for:

- member entry
- member exit
- access denied
- device online/offline
- device synchronization changes
- security alarms
- important notifications.

Never make the frontend poll every few seconds as the primary solution.

Polling can be a fallback for specific health/reconciliation views.

---

# 18. DEVICE DASHBOARD

Create a beautiful Devices section.

Each device card should show:

- device name
- location
- role: ENTRY / EXIT
- IP/network status
- gateway status
- SDK connection status
- last heartbeat
- last successful synchronization
- pending sync commands
- firmware/software information if available
- number of local users where queryable
- local record count where queryable
- time drift if queryable
- last error
- reconnect count
- health score/status.

Actions:

- Test connection
- Synchronize
- Reconcile
- Refresh device users
- View events
- View alarms
- Query device time
- Sync time
- Reboot device — elevated role only
- Remote door open — extremely restricted, confirmation + reason + audit
- Remote door close — restricted
- Configure device — restricted
- Firmware update — highly restricted and behind explicit confirmation

Dangerous actions require confirmation dialogs that clearly explain consequences.

---

# 19. MEMBER MANAGEMENT UX

Members page:

- search
- filters
- membership status
- access status
- expiry date
- last visit
- attendance frequency
- payment state
- device sync state
- tags

Member profile should include:

Overview
Membership
Attendance
Payments
Access
Biometric/Device Enrollment
Notes
Communication
Audit

Make the member profile the single operational view for staff.

Provide quick actions:

- Edit member
- Renew
- Freeze
- Unfreeze
- Cancel membership
- Record payment
- Enroll face
- Resync device
- View attendance
- Send message
- View audit history

---

# 20. MEMBERSHIP BUSINESS RULES

Support:

- membership plans
- start date
- end date
- active
- upcoming
- expired
- frozen
- cancelled
- overdue
- grace period
- renewal
- extension
- pause/freeze
- reactivation
- multiple historical memberships

Do not overwrite history when renewing.

Use immutable historical records where appropriate.

Example:

Current membership:
01 Sep → 30 Sep

Renew:
→ preserve old membership history
→ create/update current membership according to business rules
→ create device synchronization command
→ show device sync result.

---

# 21. ATTENDANCE / ACCESS UI

Build:

- live attendance feed
- entry/exit timeline
- member attendance history
- today’s attendance
- date-range attendance
- denied access events
- unknown credential events if available
- device source
- access method
- entry/exit direction
- reason/status
- filters.

For each event show:

- member photo/avatar if available from safe application data
- member name
- member ID
- time
- direction
- device
- access method
- result
- membership state at time
- synchronization source
- event confidence/quality only when actually supplied by the device.

Do not invent biometric confidence scores.

---

# 22. OWNER DASHBOARD

Make this a premium “business command center”.

Top-level KPIs:

- Members active
- Members expiring soon
- Expired memberships
- New members
- Revenue/payment summary
- Today’s check-ins
- Today’s unique visitors
- Inactive members
- Devices online
- Pending device sync
- Attendance trend

Visuals:

- attendance trend
- peak hours
- new vs returning members
- membership status distribution
- expiry pipeline
- inactive member segment
- device health

Use charts that are actually useful to a gym owner, not decorative graphs.

Provide “Things that need attention”:

- 8 memberships expire in 3 days
- 5 members inactive for 14+ days
- Device B has 3 failed synchronization commands
- Device A has not reconciled since 10:20 AM

Make these actionable.

---

# 23. ANALYTICS

Implement useful analytics:

- daily attendance
- weekly attendance
- monthly attendance
- peak gym hours
- busiest days
- member visit frequency
- inactive members
- attendance drop-off
- upcoming expiry
- expired membership
- membership renewal rate if data permits
- payment summary
- device health trend

Use server-side aggregation for larger datasets.

Do not load thousands of raw events into React just to calculate statistics in the browser.

---

# 24. NOTIFICATIONS

Support a notification abstraction:

NotificationChannel
→ EMAIL
→ SMS
→ IN_APP

Add:

- expiry reminders
- inactivity reminders
- announcements
- payment reminders
- membership renewal messages
- holiday/closure notices.

Build templates with variables such as:

{{memberName}}
{{gymName}}
{{expiryDate}}
{{daysRemaining}}

Queue outbound notifications asynchronously.

Record:

- channel
- template
- recipient
- status
- retry count
- sent timestamp
- provider response/error

Do not hardwire a vendor.

Use a provider interface.

Provide mock provider in development.

---

# 25. PUBLIC WEBSITE

Include a professional public-facing gym website as a route/application area.

Pages:

Home
About
Services
Membership Plans
Facilities
Contact
Enquiry/Lead Form

The design should look like a premium modern gym.

Use:

- high quality hero section
- strong typography
- subtle motion
- meaningful imagery
- pricing cards
- testimonials/demo content clearly labeled as sample content
- CTA sections
- contact form
- mobile responsive layout
- excellent footer
- SEO metadata
- accessible semantic HTML.

Do not make the public site look like the admin dashboard.

---

# 26. FRONTEND TECHNOLOGY

Use:

- React
- TypeScript
- Vite
- modern React Router
- TanStack Query
- React Hook Form
- Zod
- TanStack Table
- a modern accessible component system such as shadcn/ui/Radix where appropriate
- Lucide icons
- charting library such as Recharts
- date-fns
- motion/transition system appropriate for React 19
- modern CSS with responsive layouts.

Use React 19.3 if it is still the current stable release at implementation time.

Use the latest stable Vite/TypeScript/dependency versions that are mutually compatible.

Do not blindly choose “latest” package versions. Verify compatibility.

Keep third-party libraries minimal and justified.

---

# 27. VISUAL DESIGN SYSTEM

The UI must be genuinely stunning.

Target visual character:

- premium
- modern
- confident
- athletic
- clean
- professional
- trustworthy

Avoid:

- dated bootstrap look
- excessive gradients
- clutter
- tiny text
- giant rounded cards everywhere
- meaningless animations
- excessive glassmorphism
- template-looking dashboards.

Use:

- strong typography hierarchy
- generous spacing
- consistent 8px-based rhythm where appropriate
- restrained color system
- high contrast
- clear destructive action styling
- excellent empty states
- skeleton loading
- polished hover/focus states
- smooth transitions
- responsive tables
- mobile drawers/sheets
- sticky action areas where useful.

Recommended font direction:
Use a modern professional sans-serif family such as Inter, Manrope, Geist, or another contemporary UI font. Verify licensing and loading strategy.

Icons:
Lucide preferred unless another icon set is clearly more consistent.

Use React 19.3 View Transitions where appropriate for route/view transitions, but always preserve accessibility and reduced-motion preferences.

---

# 28. RESPONSIVENESS

The web app must work well on:

- 1440px desktop
- 1280px laptop
- 1024px tablet landscape
- 768px tablet
- 430px mobile
- 390px mobile
- 360px mobile

Do not simply shrink desktop layouts.

For mobile:

- use bottom sheets/drawers
- convert tables into cards where necessary
- provide sticky primary actions
- collapse navigation intelligently
- maintain large tap targets
- do not require horizontal scrolling for ordinary workflows.

---

# 29. ACCESSIBILITY

Implement:

- keyboard navigation
- visible focus
- semantic HTML
- accessible labels
- ARIA only where necessary
- color contrast
- reduced motion support
- non-color-only status communication
- screen-reader-friendly dialogs
- accessible tables
- accessible form errors.

Target WCAG 2.2 AA where practical.

---

# 30. FRONTEND ROUTES

Create all major routes and make them fully functional.

Suggested route hierarchy:

/
/about
/services
/membership-plans
/contact

/app/login
/app/forgot-password

/app/dashboard
/app/members
/app/members/new
/app/members/:id
/app/members/:id/edit
/app/members/:id/membership
/app/members/:id/attendance
/app/members/:id/payments
/app/members/:id/access
/app/members/:id/enrollment

/app/attendance
/app/attendance/live

/app/memberships
/app/plans
/app/payments

/app/devices
/app/devices/:id
/app/devices/:id/events
/app/devices/:id/sync
/app/devices/:id/settings

/app/notifications
/app/notifications/templates
/app/announcements

/app/reports
/app/reports/attendance
/app/reports/memberships
/app/reports/payments
/app/reports/devices

/app/users
/app/roles

/app/audit
/app/settings
/app/profile

Create proper protected-route behavior and permission-based rendering.

---

# 31. STATE MANAGEMENT

Prefer server state through TanStack Query.

Use local component state whenever possible.

Use a small global client-state store only for genuine global UI/application state, e.g.:

- authenticated user summary
- theme
- sidebar state
- command palette
- live connection status

Do not build a giant Redux-like state machine for server data.

---

# 32. ERROR / LOADING UX

Every async screen must handle:

- loading
- skeleton
- empty
- error
- retry
- stale data
- success feedback.

Do not show blank screens while APIs load.

For mutations:

- disable duplicate submissions
- show progress
- show success
- show meaningful failure
- preserve form input on validation/network failure.

---

# 33. API / FRONTEND CONTRACT

Keep frontend API types strongly typed.

Generate or maintain TypeScript API types from OpenAPI where practical.

Do not manually duplicate every backend DTO in an unrelated shape.

Use a clear API client layer.

Centralize:

- auth
- base URL
- headers
- error normalization
- retry behavior
- correlation ID.

WebSocket/SSE client must support reconnect.

---

# 34. TESTING

Backend:

- unit tests
- repository tests where useful
- service tests
- controller/API tests
- Spring Security authorization tests
- integration tests
- Testcontainers for MySQL
- device gateway adapter tests with mocks

Frontend:

- component tests
- form tests
- route/protected-route tests
- API-state tests
- accessibility checks
- critical workflow tests

End-to-end:

- Playwright or equivalent

Critical end-to-end scenarios:

1. Login
2. Add member
3. Create membership
4. Enroll member
5. Synchronize member to device
6. Device goes offline
7. Device reconnects
8. Attendance event arrives
9. Attendance appears live
10. Membership expires
11. Authorization sync is generated
12. Device synchronization succeeds
13. Membership renewal
14. New authorization sync
15. Staff role cannot execute owner-only operation
16. Unauthorized user cannot access another tenant
17. Remote door action generates audit log

---

# 35. OBSERVABILITY

Implement:

- Spring Boot Actuator
- health endpoints
- readiness/liveness concepts
- structured JSON logging
- correlation ID
- request ID
- device gateway connection metrics
- synchronization metrics
- failed command metrics
- attendance ingestion metrics
- notification metrics

Never log:

- passwords
- access tokens
- refresh tokens
- device credentials
- face templates
- raw biometric images
- other sensitive personal data unnecessarily.

---

# 36. DEVOPS / DEPLOYMENT

Provide Docker support.

Create:

- Dockerfile for backend
- Dockerfile for frontend if needed
- Dockerfile/package for device gateway where practical
- docker-compose.yml for development
- MySQL
- backend
- frontend
- optionally a local gateway simulator

Provide `.env.example`.

Provide production-minded configuration profiles.

Frontend should ideally be served behind a reverse proxy.

Use HTTPS in production.

Document how the local Device Gateway connects to the cloud backend.

Do not require inbound internet access to the gym network unless absolutely necessary.

---

# 37. DEVICE GATEWAY COMMUNICATION CONTRACT

Define an explicit gateway protocol.

Example:

Gateway → Backend:

REGISTER_GATEWAY
HEARTBEAT
DEVICE_STATUS
DEVICE_EVENT
DEVICE_ALARM
SYNC_RESULT
RECONCILIATION_RESULT
DEVICE_METADATA
ENROLLMENT_RESULT

Backend → Gateway:

SYNC_USER
UPDATE_USER
DISABLE_USER
ENABLE_USER
DELETE_USER
UPDATE_VALIDITY
UPDATE_ACCESS_POLICY
START_ENROLLMENT
RECONCILE_DEVICE
SYNC_TIME
REMOTE_DOOR_COMMAND
REQUEST_DEVICE_STATUS

Every message should include:

messageId
timestamp
gatewayId
deviceId
type
correlationId
payload

Make command handling idempotent.

Return acknowledgements.

Handle reconnect replay safely.

---

# 38. GATEWAY DEVICE ADAPTER

Create something conceptually like:

public interface DeviceAdapter {
    DeviceConnectionStatus connect(DeviceConnectionConfig config);
    void disconnect();
    DeviceInfo getDeviceInfo();
    DeviceHealth getHealth();

    DeviceUser createUser(...);
    DeviceUser updateUser(...);
    void disableUser(...);
    void enableUser(...);
    void deleteUser(...);

    EnrollmentSession startFaceEnrollment(...);

    List<DeviceAttendanceRecord> fetchAttendance(...);

    void registerEventListener(DeviceEventListener listener);

    void openDoor(...);
    void closeDoor(...);

    void synchronizeTime(...);

    DeviceReconciliationResult reconcile(...);
}

Then:

TrueFaceDeviceAdapter
MockDeviceAdapter

All native SDK implementation details stay inside TrueFaceDeviceAdapter.

---

# 39. NATIVE SDK SAFETY RULE

The SDK may expose native callbacks.

Do NOT:

- block callback threads with database writes
- execute long business logic inside native callbacks
- let callback exceptions crash the SDK process
- leak native handles
- leak unmanaged memory
- assume callback lifetime without verifying SDK semantics.

Instead:

SDK callback
→ normalize immediately
→ enqueue internal event
→ process asynchronously.

Add graceful shutdown:

- stop callbacks
- close sessions
- release native resources
- terminate background threads
- flush queues where appropriate.

---

# 40. DEVICE DISCOVERY / INITIAL SETUP

Build an admin workflow:

Devices
→ Add device
→ Enter device name
→ choose ENTRY/EXIT
→ IP / host
→ port
→ credentials
→ connection test
→ fetch device metadata
→ save
→ gateway mapping
→ initial reconciliation
→ sync status

Support manual IP configuration.

Where the SDK supports device discovery, keep discovery as a diagnostic/setup feature rather than a mandatory runtime dependency.

---

# 41. DATA MODEL FOR DEVICE MAPPING

A member may exist in the application database while having a device-specific identity.

Do NOT make application member ID equal to device user ID blindly.

Use:

Member
  +
Device
  +
MemberDeviceMapping

Mapping should contain:

- memberId
- deviceId
- deviceUserId
- enrollment state
- credential states
- validity synchronization state
- last synchronization timestamp
- last device response
- enabled/disabled state.

This is essential for multiple devices.

---

# 42. ENTRY / EXIT SEMANTICS

Do not assume that device event type alone means entry/exit.

Configure each device:

deviceRole = ENTRY
or
deviceRole = EXIT

Then normalize the resulting event into:

direction = ENTRY
or
direction = EXIT

Store the raw device event separately when useful for troubleshooting.

This makes the application resilient if the same TrueFace model is used differently at another site.

---

# 43. AUDIT LOGGING

Audit all sensitive operations:

- login
- password changes
- role changes
- member creation
- member changes
- membership renewal
- freeze/unfreeze
- cancellation
- payment changes
- biometric enrollment
- device changes
- device authorization changes
- remote door actions
- device reboot
- firmware operations
- configuration changes
- manual synchronization
- audit access.

Audit record:

actor
tenant
timestamp
action
resource
resourceId
before
after
ip
userAgent
correlationId
result

Do not put sensitive biometric values in audit logs.

Make audit history tamper-resistant at the application level.

---

# 44. PAYMENT / BILLING

For MVP, support recording payments rather than assuming an online payment gateway.

Payment fields:

- amount
- date
- method
- reference
- status
- membership
- received by
- notes

Allow future payment-provider integration behind an interface.

Do not hardwire Stripe/Razorpay into domain logic unless explicitly needed.

---

# 45. SEARCH / FILTERS

Members:

- name
- phone
- member number
- status
- expiry
- plan
- device enrollment
- attendance activity

Attendance:

- date range
- member
- device
- entry/exit
- access method
- result

Devices:

- status
- role
- gateway
- location

Use debounced search on frontend.

Use server-side filtering for large datasets.

---

# 46. UX MICROINTERACTIONS

Add subtle, purposeful interactions:

- row hover
- button press feedback
- optimistic visual states when safe
- dialog transitions
- page transitions
- live event highlight
- success checkmark
- skeleton shimmer
- sync state animations
- device online/offline transitions
- notification badge animations.

Respect prefers-reduced-motion.

Never use animation just because it is possible.

---

# 47. COMMAND PALETTE

Implement a polished command palette if it improves UX.

Example commands:

- Add member
- Search member
- Open attendance
- Open devices
- View expiring memberships
- Run synchronization
- Create announcement

This is a premium product detail.

---

# 48. MOBILE-FIRST STAFF EXPERIENCE

On mobile, staff should be able to:

- search member
- open member profile
- renew membership
- record payment
- check attendance
- see device health
- enroll member
- view sync status

Do not make the staff open six pages to perform a routine action.

---

# 49. DEMO / SEED DATA

Provide development seed data for:

- 1 gym tenant
- 1 owner
- 1 admin
- 2 staff accounts
- 20–50 members
- active memberships
- expired memberships
- frozen member
- overdue payment
- two devices
- simulated attendance
- simulated device sync failures
- notifications
- audit history.

Clearly label seeded/demo content.

Do not use fake production secrets.

---

# 50. DEVICE SIMULATOR

Because actual hardware may not always be connected during development, build a local Device Simulator.

It should allow developers to:

- mark device online/offline
- create simulated member
- emit entry event
- emit exit event
- emit denied event
- emit alarm
- simulate reconnect
- simulate delayed ACK
- simulate command failure
- simulate duplicate event
- simulate missing event followed by reconciliation.

This will dramatically improve development and testing before the physical hardware is available.

---

# 51. SECURITY / PRIVACY FOR BIOMETRICS

Treat biometric information as highly sensitive.

Principles:

- data minimization
- least privilege
- encryption in transit
- encryption at rest where applicable
- no raw biometric data in logs
- limited retention
- explicit audit trail
- clear deletion behavior
- device-local biometric storage preferred when technically possible
- no biometric processing algorithm in application
- no biometric data returned to the browser unless absolutely required.

Create an architecture note documenting where biometric data exists and why.

---

# 52. PERFORMANCE TARGETS

Design for the initial baseline:

- 1,000 members
- 4–5 admins/staff
- 2 devices
- approximately 500–800 daily check-ins
- significant historical attendance volume
- device capacity up to 300,000 records

The system should comfortably handle this load without premature microservices.

Keep the architecture modular monolith + local gateway.

Do NOT turn this into 12 microservices.

---

# 53. LOGICAL DEPLOYMENT MODES

Support two conceptual deployment modes:

A. CLOUD

React
→ Spring Boot
→ MySQL
↕
Secure gateway connection
↕
TrueFace devices

B. LOCAL

React
→ Spring Boot
→ MySQL
↕
Device Gateway
↕
TrueFace devices

Keep the same application architecture.

---

# 54. API / DEVICE INTEGRATION FAILURE IS A FIRST-CLASS STATE

Never hide uncertainty.

For every SDK-dependent feature, distinguish:

SUPPORTED_AND_VERIFIED
SUPPORTED_BY_DOCUMENTATION
IMPLEMENTED_BUT_HARDWARE_UNAVAILABLE
MOCKED
NOT_SUPPORTED
NOT_YET_VERIFIED

Use this in developer documentation and possibly diagnostics.

The UI may show user-friendly status, but internal diagnostics should preserve this distinction.

---

# 55. DOCUMENTATION TO GENERATE

Create:

README.md
ARCHITECTURE.md
SECURITY.md
DEVICE-INTEGRATION.md
DEVICE-SDK-NOTES.md
DATABASE.md
API.md
DEPLOYMENT.md
DEVELOPMENT.md
TESTING.md
TROUBLESHOOTING.md

Also create an architecture diagram using Mermaid.

Document:

- system architecture
- request flows
- attendance flow
- authorization sync flow
- enrollment flow
- reconnect flow
- reconciliation flow
- security model
- tenant isolation
- deployment topology
- device gateway
- SDK boundary
- known limitations.

---

# 56. README MUST INCLUDE

- prerequisites
- Java version
- Node version
- MySQL setup
- environment variables
- how to run backend
- how to run frontend
- how to run gateway
- how to run simulator
- how to run tests
- how to build production artifacts
- Docker instructions
- initial login credentials for development
- how to replace mock adapter with real SDK
- how to connect the TrueFace device
- troubleshooting.

---

# 57. QUALITY GATES

Before considering work complete, verify:

BACKEND
- project builds successfully
- tests pass
- migrations run
- security works
- unauthorized API calls fail
- tenant isolation works
- validation works
- OpenAPI loads
- actuator loads
- no compile warnings that can be reasonably fixed

FRONTEND
- production build succeeds
- routes work
- no broken navigation
- no console errors
- mobile layout works
- loading/error states work
- protected routes work
- permissions are respected
- no hardcoded backend URL where environment config is expected

GATEWAY
- simulator works
- reconnect works
- retry works
- event normalization works
- deduplication works
- command correlation works
- adapter boundary is isolated

DATABASE
- migrations are deterministic
- indexes exist for major search patterns
- foreign keys are valid
- no accidental cascade deletion of historical/audit information

UX
- empty states are intentional
- destructive actions require confirmation
- forms have validation
- mobile workflows are usable
- transitions are subtle
- visuals feel coherent across all pages.

---

# 58. ANTI-PATTERNS — DO NOT DO THESE

DO NOT:

- put SDK calls in controllers
- expose JPA entities directly
- use one giant service class
- store JWTs in localStorage by default
- put device credentials in frontend
- assume iAS is required
- make iAS and our app two competing sources of truth
- implement face-recognition algorithms
- fake hardware operations when actual SDK behavior is unknown
- poll the device aggressively for real-time events
- lose attendance events on reconnect
- silently discard failed sync commands
- use in-memory synchronization queues as the only durability mechanism
- use Thread.sleep loops as a “retry system”
- hardcode a single device
- hardcode ENTRY/EXIT based on ID
- use database IDs as device IDs without mapping
- create microservices unnecessarily
- use massive frontend global state
- put business logic in React components
- use arbitrary external image links everywhere
- add animation that harms accessibility
- ship demo passwords or secrets to production
- disable Spring Security “temporarily”
- disable CORS/CSRF broadly just to make the frontend work.

---

# 59. IMPORTANT COPILOT EXECUTION RULES

1. Inspect repository structure first.
2. Detect existing code before adding duplicates.
3. Preserve useful existing code unless it conflicts with the architecture.
4. Prefer incremental implementation over a giant speculative rewrite.
5. After each substantial phase, run build/tests and fix errors.
6. Never leave obvious compilation failures behind.
7. Use exact SDK signatures from available files/docs.
8. Where SDK artifacts are absent, implement the adapter boundary + simulator and document exactly what is missing.
9. Do not fabricate credentials, IP addresses, or device responses.
10. Use environment variables/configuration.
11. Keep code production quality.
12. Use comments for WHY, not for obvious WHAT.
13. Prefer straightforward maintainable code over clever abstractions.
14. Build complete workflows, not disconnected screens.
15. Every UI action that calls an API must have a real backend endpoint or be explicitly a simulator-only operation.
16. No fake buttons.
17. No dead routes.
18. No unfinished placeholder pages for core functionality.
19. Use realistic seeded development data.
20. When a requirement is uncertain, make the uncertainty explicit in code/docs and isolate the uncertain integration rather than guessing.

---

# 60. IMPLEMENTATION ORDER

Work in this order:

PHASE 0
Repository inspection
Architecture decision record
Project structure
Dependency/version verification

PHASE 1
Backend foundation
Database
Flyway
Tenant model
Security
RBAC
Authentication
Audit

PHASE 2
Members
Memberships
Plans
Payments
Access policy

PHASE 3
Device domain
Device Gateway contract
Gateway simulator
Synchronization/outbox
Device health

PHASE 4
TrueFace SDK adapter boundary
Native SDK integration where exact documentation/support is available
Connection/reconnect
Device events
Attendance reconciliation
Device authorization synchronization

PHASE 5
React design system
Shell/navigation
Authentication
Dashboard
Members
Membership
Attendance
Devices
Reports
Notifications
Settings

PHASE 6
Live WebSocket updates
Mobile polish
Animations
Public website
Enquiries

PHASE 7
Testing
E2E
Security review
Performance review
Accessibility review

PHASE 8
Docker
Deployment
Documentation
Production hardening

---

# 61. FIRST DELIVERABLE

Before writing large quantities of code, produce:

1. final architecture summary
2. repository/module tree
3. dependency/version table
4. database ERD in Mermaid
5. major sequence diagrams in Mermaid
6. device gateway protocol
7. security model
8. route map
9. major UI screen inventory
10. explicit list of SDK-dependent operations
11. explicit list of SDK behavior that remains unverified
12. implementation phases

Then proceed to implementation without waiting for additional approval unless the repository is genuinely missing critical information.

---

# 62. CURRENT VERSION POLICY

The current date may be later than the knowledge embedded in this prompt.

For every major framework/library:

- verify the current stable release using official documentation/package metadata
- choose mutually compatible versions
- prefer supported/stable releases over previews
- pin versions in package/build files
- record the chosen versions in documentation.

Technology preferences:

Backend:
- Java 21
- Spring Boot latest stable compatible with Java 21
- Spring Security latest stable compatible with selected Spring Boot
- Spring Data JPA
- MySQL
- Flyway
- Maven
- WebSocket
- Actuator
- Testcontainers

Frontend:
- React latest stable
- TypeScript latest stable
- Vite latest stable
- React Router latest stable
- TanStack Query latest stable
- React Hook Form latest stable
- Zod latest stable
- TanStack Table latest stable
- accessible component system
- Lucide
- chart library
- date utility
- animation library appropriate for current React.

Do not upgrade a dependency solely because its numeric version is newer if compatibility is uncertain.

---

# 63. FINAL PRODUCT STANDARD

This is not an educational toy.

Code quality should be appropriate for an application that could be sold to multiple gyms.

The architecture should make it possible later to add:

- additional biometric devices
- additional gates
- mobile member app
- online payments
- WhatsApp/SMS provider
- multiple branches
- staff attendance
- visitor management
- QR/mobile access
- CRM
- advanced analytics
- subscriptions
- multi-location SaaS administration.

But do not implement speculative features merely to inflate the project.

Build the core extremely well.

The most important principle is:

THE GYM APPLICATION IS THE BUSINESS SOURCE OF TRUTH.
THE DEVICE IS THE BIOMETRIC/ACCESS-CONTROL ENGINE.
THE DEVICE GATEWAY IS THE INTEGRATION BOUNDARY.
THE UI MUST MAKE THE COMPLEXITY FEEL SIMPLE.

Start by inspecting the repository and the available SDK artifacts, then execute the implementation plan.
