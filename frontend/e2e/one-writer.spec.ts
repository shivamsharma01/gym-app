import { expect, test } from '@playwright/test'

const user = {
  id: 'u1',
  username: 'v1-admin',
  email: 'v1-admin@gym.local',
  fullName: 'Test v1-admin',
  tenantId: 't1',
  roles: ['GYM_ADMIN'],
  permissions: ['DEVICE_VIEW', 'DEVICE_MANAGE', 'DEVICE_SYNC', 'MEMBER_VIEW'],
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

function device(projectionEnabled: boolean) {
  return {
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
    projectionEnabled,
    createdAt: '2026-10-09T00:00:00Z',
  }
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
  createdAt: '2026-10-09T00:00:00Z',
  coverageStatus: 'NONE',
}

test('a flagged reader hides the old sync panel', async ({ page }) => {
  await mockStaff(page, true)
  await page.goto('/app/devices/dev-1')
  await expect(page.getByRole('heading', { name: 'Entrance' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Sync Now' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Import device users' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Request attendance reconcile' })).toBeVisible()
})

test('an unflagged reader still shows the old sync panel', async ({ page }) => {
  await mockStaff(page, false)
  await page.goto('/app/devices/dev-1')
  await expect(page.getByRole('heading', { name: 'Entrance' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Sync Now' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Import device users' })).toBeVisible()
})

test('a flagged reader hides the member sync panel', async ({ page }) => {
  await mockStaff(page, true)
  await page.goto('/app/members/mem-1')
  await expect(page.getByRole('heading', { name: 'Asha Shah' })).toBeVisible()
  await expect(page.getByText('Device sync')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Read from device' })).toHaveCount(0)
})

async function mockStaff(page: import('@playwright/test').Page, projectionEnabled: boolean) {
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
      await route.fulfill({ json: device(projectionEnabled) })
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
              projectionEnabled,
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
    await route.fulfill({ status: 404, json: {} })
  })
}
