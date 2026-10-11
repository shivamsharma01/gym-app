import { expect, test } from '@playwright/test'

const user = {
  id: 'u1',
  username: 'v1-admin',
  email: 'v1-admin@gym.local',
  fullName: 'Test v1-admin',
  tenantId: 't1',
  roles: ['GYM_ADMIN'],
  permissions: ['DEVICE_VIEW', 'DEVICE_MANAGE', 'DEVICE_SYNC', 'MEMBER_VIEW', 'MEMBER_CREATE', 'MEMBERSHIP_VIEW', 'REVIEW_DECIDE'],
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
  lastPunchAt: null,
  openConflictCount: 0,
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

const membership = {
  id: 'ms-1',
  planName: 'Monthly',
  price: 1000,
  currency: 'INR',
  startDate: '2026-10-01',
  endDate: '2026-10-31',
  endDateInferred: false,
  status: 'ACTIVE',
  effectiveStatus: 'ACTIVE',
  paymentStatus: 'PAID',
  amountPaid: 1000,
  deviceSyncState: 'NOT_SYNCED',
  cancelledOn: null,
  cancelReason: null,
  createdAt: '2026-10-01T00:00:00Z',
  discountAmount: 0,
  netAmount: 1000,
}

test('a reader shows its last stored punch and offers no reconcile or member import', async ({ page }) => {
  await mockStaff(page)
  await page.goto('/app/devices/dev-1')
  await expect(page.getByRole('heading', { name: 'Entrance' })).toBeVisible()
  await expect(page.getByText(/Last punch stored/)).toBeVisible()
  await expect(page.getByRole('button', { name: 'Sync Now' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Import device users' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /reconcile/i })).toHaveCount(0)
})

test('a punch stored by the gateway appears once in the attendance list without a reload', async ({ page }) => {
  await page.clock.install()
  await mockStaff(page)
  const rows: object[] = []
  await page.route('**/api/v1/attendance?**', async (route) => {
    await route.fulfill({ json: { content: rows, totalElements: rows.length, totalPages: 1, number: 0, size: 30 } })
  })
  await page.goto('/app/attendance')
  await expect(page.getByText('No attendance yet')).toBeVisible()

  rows.push({
    id: 'att-1',
    occurredAt: '2026-10-11T05:00:00Z',
    direction: 'IN',
    method: 'FACE',
    result: 'GRANTED',
    deviceUserId: '1',
    deviceRecNo: 7,
    memberLinked: true,
    memberName: 'Asha Shah',
    createdAt: '2026-10-11T05:00:05Z',
  })
  await page.clock.runFor(16_000)

  await expect(page.locator('tbody tr')).toHaveCount(1)
  await expect(page.locator('tbody tr').first()).toContainText('Asha Shah')
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
  await expect(page.getByRole('button', { name: 'Send again' })).toHaveCount(2)
  await expect(page.getByRole('button', { name: 'Remove from reader' })).toHaveCount(2)
})

test('a member page shows each reader as applied or waiting and no membership sync label', async ({ page }) => {
  await mockStaff(page)
  await page.goto('/app/members/mem-1')
  await expect(page.getByText('On reader')).toBeVisible()
  await expect(page.getByText('Revision 2; reader applied 2')).toBeVisible()
  await expect(page.getByText('Waiting for reader')).toBeVisible()
  await expect(page.getByText('Revision 1; reader applied 0')).toBeVisible()
  await expect(page.getByText('Monthly')).toBeVisible()
  await expect(page.getByText(/device NOT_SYNCED/)).toHaveCount(0)
  await expect(page.getByText(/NOT_SYNCED/)).toHaveCount(0)
})

test('creating a member sends one request with a face and no reader choice', async ({ page }) => {
  const created: { contentType: string; body: string }[] = []
  await mockStaff(page, created)
  await page.goto('/app/members/new')
  await expect(page.getByRole('heading', { name: 'New member' })).toBeVisible()
  await expect(page.getByText('Select a reader')).toHaveCount(0)

  await page.locator('input[name="firstName"]').fill('Asha')
  await page.getByRole('button', { name: 'Save member' }).click()
  await expect(page.getByText('A face photo is required')).toBeVisible()
  expect(created).toHaveLength(0)

  const png = await page.evaluate(() => {
    const canvas = document.createElement('canvas')
    canvas.width = 240
    canvas.height = 240
    canvas.getContext('2d')!.fillRect(0, 0, 240, 240)
    return canvas.toDataURL('image/png').split(',')[1]
  })
  await page
    .locator('input[type="file"][accept="image/jpeg,image/png"]')
    .setInputFiles({ name: 'face.png', mimeType: 'image/png', buffer: Buffer.from(png, 'base64') })
  await page.getByRole('button', { name: 'Save member' }).click()
  await expect(page).toHaveURL(/\/app\/members\/mem-1$/)

  expect(created).toHaveLength(1)
  expect(created[0].contentType).toContain('multipart/form-data')
  expect(created[0].body).toContain('name="member"')
  expect(created[0].body).toContain('name="face"')
  expect(created[0].body).not.toContain('readerId')
})

async function mockStaff(
  page: import('@playwright/test').Page,
  created: { contentType: string; body: string }[] = [],
) {
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
    if (path === '/api/v1/members/next-serial') {
      await route.fulfill({ json: { serialNumber: '2' } })
      return
    }
    if (path === '/api/v1/members' && method === 'POST') {
      created.push({
        contentType: route.request().headers()['content-type'] ?? '',
        body: route.request().postDataBuffer()?.toString('latin1') ?? '',
      })
      await route.fulfill({ status: 201, json: member })
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
              deviceUserId: '2',
              revision: 2,
              appliedRevision: 2,
              applied: true,
              present: true,
              facePresent: true,
            },
            {
              deviceId: 'dev-2',
              deviceName: 'Exit',
              connectionState: 'OFFLINE',
              hasGateway: true,
              deviceUserId: '1',
              revision: 1,
              appliedRevision: 0,
              applied: false,
              present: true,
              facePresent: true,
            },
          ],
        },
      })
      return
    }
    if (path === '/api/v1/members/mem-1/memberships') {
      await route.fulfill({ json: [membership] })
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
