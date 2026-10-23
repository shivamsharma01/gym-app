export type PageResponse<T> = {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

export type BootstrapReport = {
  runId: string
  rows: BootstrapRow[]
}

export type BootstrapRow = {
  outcome: string
  deviceId: string
  deviceUserId: string
  memberId: string | null
  readerName: string | null
  serverName: string | null
  suggestionMemberId: string | null
}

export type ReviewItem = {
  id: string
  kind: 'REVIEW' | 'ENROLLMENT' | 'SUSPENDED'
  deviceId: string
  deviceUserId: string
  serverName: string | null
  readerName: string | null
  baselineName: string | null
  readerAbsent: boolean
  open: boolean
  decision: string | null
  actor: string | null
  priorState: string | null
  chosenState: string | null
  revision: number | null
  verificationError: string | null
  serverValidFrom: string | null
  serverValidTo: string | null
  readerValidFrom: string | null
  readerValidTo: string | null
  baselineValidFrom: string | null
  baselineValidTo: string | null
  serverUserStatus: number | null
  readerUserStatus: number | null
  baselineUserStatus: number | null
  readerPhotoDiffers: boolean
  deviceName?: string | null
  /** The member a review is about; groups one member's items across readers. */
  memberId?: string | null
  memberName?: string | null
  /** A member an unknown reader person may already be. Never linked automatically. */
  possibleMemberId?: string | null
  possibleMemberName?: string | null
}

export type UserSummary = {
  id: string
  username: string
  email: string
  fullName: string
  tenantId: string | null
  roles: string[]
  permissions: string[]
}

export type TokenResponse = {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
  user: UserSummary
}

export type Member = {
  id: string
  memberCode: string
  firstName: string
  lastName: string
  fullName: string
  email: string | null
  phone: string | null
  dateOfBirth: string | null
  gender: string
  status: string
  joinedOn: string
  notes: string | null
  deviceAuthority?: 'USER' | 'ADMIN'
  createdAt: string
  coverageStatus: string
  /** Per-reader device user id and sync marker. `seen` is an id the reader already has for this photo. */
  readers?: {
    deviceId: string
    deviceName: string
    deviceUserId: string | null
    /** none | ok | pending | held | failed | absent | seen */
    syncState?: string
  }[]
}

export type Plan = {
  id: string
  name: string
  description: string | null
  price: number | string
  currency: string
  durationDays: number
  status: string
  createdAt: string
}

export type Membership = {
  id: string
  planName: string
  price: number | string
  currency: string
  startDate: string
  endDate: string
  status: string
  effectiveStatus: string
  paymentStatus: string
  amountPaid: number | string
  cancelledOn: string | null
  cancelReason: string | null
  createdAt: string
  discountAmount: number | string
  netAmount: number | string
}

export type PaymentSummary = {
  totalAmount: number | string
  paymentCount: number
}

export type AccessStatus = {
  memberId: string
  allowed: boolean
  reason: string
  membershipId: string | null
  membershipStatus: string | null
  validUntil: string | null
  paymentStatus: string | null
}

export type Payment = {
  id: string
  memberId: string
  memberName: string
  amount: number | string
  currency: string
  method: string
  status: string
  reference: string | null
  paidOn: string
  receivedBy: string | null
  notes: string | null
  createdAt: string
}

export type Attendance = {
  id: string
  occurredAt: string
  direction: string
  reader: string | null
  method: string
  result: string
  deviceUserId: string | null
  deviceRecNo: number | null
  memberLinked: boolean
  memberName: string | null
  createdAt: string
}

export type Device = {
  id: string
  name: string
  role: string
  host: string | null
  port: number | null
  model: string | null
  serialNumber: string | null
  firmware: string | null
  connectionState: string
  lastSeenAt: string | null
  gatewayAssigned: boolean
  createdAt: string
}

export type DeviceHealth = {
  id: string
  deviceConnectionState: string
  gatewayStatus: string | null
  gatewaySessionOnline: boolean
  lastSeenAt: string | null
  lastSuccessfulSyncAt: string | null
  pendingCommandCount: number
  failedCommandCount: number
  lastPunchAt: string | null
}

export type Gateway = {
  id: string
  name: string
  status: string
  lastHeartbeatAt: string | null
  lastRegisteredAt: string | null
  agentVersion: string | null
  createdAt: string
}

export type GatewayCreated = Gateway & {
  token: string
  enrollmentExpiresAt: string | null
}

export type SyncCommand = {
  id: string
  type: string
  state: string
  attemptCount: number
  maxAttempts: number
  nextAttemptAt: string | null
  lastError: string | null
  correlationId: string
  dispatchedAt: string | null
  acknowledgedAt: string | null
  completedAt: string | null
  createdAt: string
}

export type FaceView = {
  version: number
  sha256: string
  source: 'MANUAL' | 'DEVICE'
  changedAt: string
}

export type MemberDeviceSync = {
  face: {
    version: number
    source: 'MANUAL' | 'DEVICE'
    sourceDeviceName: string | null
    changedAt: string
  } | null
  devices: {
    deviceId: string
    deviceName: string
    connectionState: string | null
    hasGateway: boolean
    /** Null when this member has no mapping on that reader yet. */
    deviceUserId: string | null
    /** This member's desired revision on this reader; null when none was published. */
    revision: number | null
    appliedRevision: number
    /** The gateway read back this row's current revision on the reader. */
    applied: boolean
    present: boolean
    facePresent: boolean
    /** The reader showed something else; nothing is written until staff decide in Reviews. */
    held: boolean
    /** Why the gateway has not applied the current revision; null when it reported nothing. */
    applyError: string | null
    applyAttempts: number | null
    applyErrorPermanent: boolean
    /** Desired validity on this reader, or the dates the reader already stores when only seen. */
    validFrom: string | null
    validTo: string | null
    /** Reader user id for this photo when there is no mapping yet. */
    seenDeviceUserId: string | null
  }[]
}

export type SecurityEvent = {
  id: string
  type: string
  occurredAt: string
  details: string | null
  acknowledged: boolean
  createdAt: string
}
