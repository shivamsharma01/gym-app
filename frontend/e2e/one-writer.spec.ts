import { expect, test } from '@playwright/test'

const user = {
  id: 'u1',
  username: 'v1-admin',
  email: 'v1-admin@gym.local',
  fullName: 'Test v1-admin',
  tenantId: 't1',
  roles: ['GYM_ADMIN'],
  permissions: ['DEVICE_VIEW', 'DEVICE_MANAGE', 'DEVICE_SYNC', 'MEMBER_VIEW', 'REVIEW_DECIDE'],
}

const health = {
  id: 'dev-1',
  deviceConnectionState: 'ONLINE',
  gatewayStatus: 'ONLINE',
  gatewaySessionOnline: true,
  lastSeenAt: null,
  lastSuccessfulSyncAt: null,
  pendingCommandCount: 0,
  failedCommandCount: 0,
  reconciliationRequired: false,
  lastAttendanceSyncAt: null,
  openConflictCount: 0,
  attendanceLastRecNo: null,
  attendanceLastEventAt: null,
}

const device = {
  id: 'dev-1',
  name: 'Entrance',
  role: 'ENTRANCE',
  host: '10.0.0.8',
  port: 37777,
  model: 'TrueFace 3000',
  serialNumber: 'SN',
  firmware: null,
  connectionState: 'ONLINE',
  lastSeenAt: null,
  gatewayAssigned: true,
  createdAt: '2026-10-09T00:00:00Z',
}

const member = {
  id: 'mem-1',
  memberCode: 'V16-ASHA',
  serialNumber: '1',
  firstName: 'Asha',
  lastName: 'Shah',
  fullName: 'Asha Shah',
  email: null,
  phone: null,
  dateOfBirth: null,
  gender: 'UNSPECIFIED',
  status: 'ACTIVE',
  joinedOn: '2026-10-09',
  notes: null,
  creationSource: 'MANUAL',
  coverageStatus: 'NONE',
  createdAt: '2026-10-09T00:00:00Z',
}

test('a reader has attendance reconcile and no member import', async ({ page }) => {
  await mockStaff(page)
  await page.goto('/app/devices/dev-1')
  await expect(page.getByRole('heading', { name: 'Entrance' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Sync Now' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Import device users' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Request attendance reconcile' })).toBeVisible()
})

test('a validity difference is shown for review and neither side is applied automatically', async ({ page }) => {
  await mockStaff(page)
  await page.goto('/app/review')
  await expect(page.getByRole('heading', { name: 'Review' })).toBeVisible()
  await expect(page.getByText('2026-10-08 – 2026-11-15')).toBeVisible()
  await expect(page.getByText('2026-10-01T00:00:00+05:30 – 2026-10-31T23:59:59+05:30')).toHaveCount(2)
  await expect(page.getByRole('button', { name: 'Accept server' })).toBeVisible()
  await expect(page.getByRole('button', { name: /accept reader|use reader|reader wins/i })).toHaveCount(0)
})

test('a member page does not offer a clock-based device read', async ({ page }) => {
  await mockStaff(page)
  await page.goto('/app/members/mem-1')
  await expect(page.getByRole('heading', { name: 'Asha Shah' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Read from device' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Send again' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Remove from reader' })).toBeVisible()
})

async function mockStaff(page: import('@playwright/test').Page) {
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    if (path === '/api/v1/auth/refresh' && method === 'POST') {
      await route.fulfill({
        json: { accessToken: 'token', tokenType: 'Bearer', expiresInSeconds: 900, user },
      })
      return
    }
    if (path === '/api/v1/me') {
      await route.fulfill({ json: user })
      return
    }
    if (path === '/api/v1/settings') {
      await route.fulfill({ json: {} })
      return
    }
    if (path.startsWith('/api/v1/gateways')) {
      await route.fulfill({ json: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } })
      return
    }
    if (path === '/api/v1/devices/dev-1' && method === 'GET') {
      await route.fulfill({ json: device })
      return
    }
    if (path === '/api/v1/devices/dev-1/health') {
      await route.fulfill({ json: health })
      return
    }
    if (path.startsWith('/api/v1/devices/dev-1/conflicts')) {
      await route.fulfill({ json: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } })
      return
    }
    if (path === '/api/v1/members/mem-1' && method === 'GET') {
      await route.fulfill({ json: member })
      return
    }
    if (path === '/api/v1/members/mem-1/device-sync') {
      await route.fulfill({
        json: {
          face: null,
          devices: [
            {
              deviceId: 'dev-1',
              deviceName: 'Entrance',
              connectionState: 'ONLINE',
              hasGateway: true,
              deviceUserId: '1',
              pendingDeviceUserId: null,
              differsFromSerial: false,
              userSyncState: 'SYNCED',
              faceSyncState: null,
              faceVersionSynced: null,
              faceLastError: null,
              openCommands: [],
            },
          ],
        },
      })
      return
    }
    if (path === '/api/v1/members/mem-1/memberships') {
      await route.fulfill({ json: [] })
      return
    }
    if (path === '/api/v1/members/mem-1/access') {
      await route.fulfill({ json: { allowed: true, reason: null } })
      return
    }
    if (path.startsWith('/api/v1/members/mem-1/attendance')) {
      await route.fulfill({ json: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } })
      return
    }
    if (path === '/api/v1/members/mem-1/payments') {
      await route.fulfill({ json: [] })
      return
    }
    if (path === '/api/v1/reviews' && method === 'GET') {
      await route.fulfill({
        json: [
          {
            id: 'rev-1',
            kind: 'REVIEW',
            deviceId: 'dev-1',
            deviceUserId: '1',
            serverName: 'Asha Shah',
            readerName: 'Asha Shah',
            baselineName: 'Asha Shah',
            readerAbsent: false,
            open: true,
            decision: null,
            actor: null,
            priorState: null,
            chosenState: null,
            revision: null,
            verificationError: null,
            serverValidFrom: '2026-10-01T00:00:00+05:30',
            serverValidTo: '2026-10-31T23:59:59+05:30',
            readerValidFrom: '2026-10-08',
            readerValidTo: '2026-11-15',
            baselineValidFrom: '2026-10-01T00:00:00+05:30',
            baselineValidTo: '2026-10-31T23:59:59+05:30',
          },
        ],
      })
      return
    }
    await route.fulfill({ status: 404, json: {} })
  })
}
