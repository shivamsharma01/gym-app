/** Closed lists for staff/public forms. Keep in sync with backend enums and gym defaults. */

export const CURRENCIES = ['INR'] as const

export const PLAN_DURATIONS = [
  { days: 30, label: '1 month (30 days)' },
  { days: 90, label: '3 months (90 days)' },
  { days: 180, label: '6 months (180 days)' },
  { days: 365, label: '1 year (365 days)' },
] as const

export const NOTIFICATION_TEMPLATE_KEYS = ['EXPIRY_REMINDER', 'WELCOME'] as const

export const NOTIFICATION_CHANNELS = ['EMAIL', 'SMS', 'IN_APP'] as const

export const DEVICE_MODELS = ['TrueFace 3000'] as const

/** Staff-login roles for /app/users. Gym members are a different record and are not listed here. */
export const STAFF_ROLES = [
  { name: 'STAFF', label: 'Staff', description: 'Day-to-day members, memberships, and payments' },
  { name: 'FRONT_DESK', label: 'Front desk', description: 'Check-in, member lookup, and taking payment' },
  { name: 'REPORT_VIEWER', label: 'Report viewer', description: 'Read-only reports' },
  { name: 'GYM_ADMIN', label: 'Gym admin', description: 'Run the gym, including staff accounts and devices' },
  { name: 'GYM_OWNER', label: 'Gym owner', description: 'Full gym access, including roles' },
] as const
