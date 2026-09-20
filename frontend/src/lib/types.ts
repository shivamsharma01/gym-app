export type PageResponse<T> = {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
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
  refreshToken: string
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
  createdAt: string
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
  deviceSyncState: string
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
  deviceSyncState: string | null
}

export type Payment = {
  id: string
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
  method: string
  result: string
  deviceUserId: string | null
  deviceRecNo: number | null
  memberLinked: boolean
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
  attendanceLastRecNo: number | null
  attendanceLastEventAt: string | null
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

export type Mapping = {
  id: string
  deviceUserId: string
  enrollmentStatus: string
  syncState: string
  enrolledAt: string | null
  createdAt: string
}

export type SecurityEvent = {
  id: string
  type: string
  occurredAt: string
  details: string | null
  acknowledged: boolean
  createdAt: string
}
