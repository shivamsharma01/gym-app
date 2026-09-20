import type { UserSummary } from '@/lib/types'

/** Platform SUPER_ADMIN has no gym tenant and must not use tenant-scoped staff UI. */
export function isPlatformSuperAdmin(user: UserSummary | null | undefined): boolean {
  return Boolean(user && user.tenantId == null && user.roles.includes('SUPER_ADMIN'))
}

export function platformHomePath() {
  return '/app/platform/gyms'
}
