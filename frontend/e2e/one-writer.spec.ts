import { expect, test } from '@playwright/test'
import { mockStaffLiveWs } from './mockLiveWs'

const user = {
  id: 'u1',
  username: 'gym-admin',
  email: 'gym-admin@gym.local',
  fullName: 'Test gym-admin',
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
  memberCode: 'ASHA-1',
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
  status: 'ACTIVE',
  effectiveStatus: 'ACTIVE',
  paymentStatus: 'PAID',
  amountPaid: 1000,
  cancelledOn: null,
  cancelReason: null,
  createdAt: '2026-10-01T00:00:00Z',
  discountAmount: 0,
  netAmount: 1000,
}

test('a reader shows its last stored punch and one reconcile action', async ({ page }) => {
  await mockStaff(page)
  await page.goto('/app/devices/dev-1')
  await expect(page.getByRole('heading', { name: 'Entrance' })).toBeVisible()
  await expect(page.getByText(/Last punch stored/)).toBeVisible()
  await expect(page.getByRole('button', { name: /reconcile/i })).toHaveCount(1)
  await expect(page.getByRole('button', { name: 'Reconcile members' })).toBeVisible()
})

test('reconciling a reader sends one request and reports what was written', async ({ page }) => {
  const reconciles: string[] = []
  await mockStaff(page, [], user, reconciles)
  await page.goto('/app/devices/dev-1')
  await page.getByRole('button', { name: 'Reconcile members' }).click()
  await expect(page.getByText(/2 members queued for this reader/)).toBeVisible()
  expect(reconciles).toEqual(['/api/v1/devices/dev-1/reconcile'])
})

test('staff without device sync see no reconcile action', async ({ page }) => {
  await mockStaff(page, [], { ...user, roles: ['STAFF'], permissions: ['DEVICE_VIEW', 'MEMBER_VIEW'] })
  await page.goto('/app/devices/dev-1')
  await expect(page.getByRole('heading', { name: 'Entrance' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Reconcile members' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Read reader now' })).toHaveCount(0)
})

test('reading a reader now sends one request and shows the gateway result', async ({ page }) => {
  const requests: string[] = []
  await mockStaff(page, [], user, requests)
  await page.goto('/app/devices/dev-1')
  await page.getByRole('button', { name: 'Read reader now' }).click()
  await expect(page.getByText('The gateway read this reader and sent its user list to the server.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Read reader now' })).toBeEnabled()
  expect(requests).toEqual(['/api/v1/devices/dev-1/scan'])
})

test('importing a reader sends one request and lists who stayed pending', async ({ page }) => {
  const requests: string[] = []
  await mockStaff(page, [], user, requests)
  await page.goto('/app/devices/dev-1')
  await page.getByRole('button', { name: 'Import pending people' }).click()
  await expect(page.getByText(/2 members created · 1 left pending/)).toBeVisible()
  await expect(page.getByText('Reader id 13: The reader face is not on the server')).toBeVisible()
  expect(requests).toEqual(['/api/v1/devices/dev-1/import-pending?mode=CONSERVATIVE'])
})

test('staff without device sync cannot import a reader', async ({ page }) => {
  await mockStaff(page, [], { ...user, roles: ['STAFF'], permissions: ['DEVICE_VIEW', 'REVIEW_DECIDE'] })
  await page.goto('/app/devices/dev-1')
  await expect(page.getByRole('heading', { name: 'Entrance' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Import pending people' })).toHaveCount(0)
})

function scanCommand(state: string) {
  return {
    id: 'scan-1',
    type: 'SCAN_READER',
    state,
    attemptCount: state === 'PENDING' ? 0 : 1,
    maxAttempts: 5,
    nextAttemptAt: null,
    lastError: null,
    correlationId: 'corr-scan-1',
    dispatchedAt: null,
    acknowledgedAt: null,
    completedAt: null,
    createdAt: '2026-10-14T00:00:00Z',
  }
}

test('a punch stored by the gateway appears once in the attendance list without a reload', async ({ page }) => {
  await page.clock.install()
  await mockStaff(page, [], { ...user, permissions: [...user.permissions, 'ATTENDANCE_VIEW'] })
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

test('a member page offers send again and remove for each reader', async ({ page }) => {
  await mockStaff(page)
  await page.goto('/app/members/mem-1')
  await expect(page.getByRole('heading', { name: 'Asha Shah' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Send again' })).toHaveCount(3)
  await expect(page.getByRole('button', { name: 'Remove from reader' })).toHaveCount(3)
})

test('a member page shows each reader as applied, not applied, or held, from that row only', async ({ page }) => {
  await mockStaff(page)
  await page.goto('/app/members/mem-1')
  await expect(page.getByText('On reader')).toBeVisible()
  await expect(page.getByText('Revision 2; read back on the reader')).toBeVisible()
  await expect(page.getByText('Not applied', { exact: true })).toBeVisible()
  await expect(page.getByText('Revision 1; not yet read back on the reader')).toBeVisible()
  await expect(page.getByText('Not applied after 6 attempts: create failed. The gateway keeps retrying.')).toBeVisible()
  await expect(page.getByText('Held for review')).toBeVisible()
  await expect(page.getByText(/Decide in Reviews before it is written/)).toBeVisible()
  await expect(page.getByText(/reader applied/)).toHaveCount(0)
  await expect(page.getByText('Monthly')).toBeVisible()
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
  who: typeof user = user,
  reconciles: string[] = [],
) {
  await mockStaffLiveWs(page)
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    if (path === '/api/v1/auth/refresh' && method === 'POST') {
      await route.fulfill({
        json: { accessToken: 'token', tokenType: 'Bearer', expiresInSeconds: 900, user: who },
      })
      return
    }
    if (path === '/api/v1/me') {
      await route.fulfill({ json: who })
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
    if (path === '/api/v1/devices/dev-1/reconcile' && method === 'POST') {
      reconciles.push(path)
      await route.fulfill({ json: { deviceId: 'dev-1', written: 2 } })
      return
    }
    if (path === '/api/v1/devices/dev-1/import-pending' && method === 'POST') {
      reconciles.push(path + url.search)
      await route.fulfill({
        json: {
          deviceId: 'dev-1',
          created: 2,
          skipped: [{ deviceUserId: '13', reason: 'The reader face is not on the server' }],
          summary: {
            deviceId: 'dev-1',
            observed: 3,
            withPhoto: 2,
            withoutPhoto: 1,
            pendingUndecided: 1,
            possibleNameMatch: 0,
            possibleFaceMatch: 0,
            readyToCreate: 0,
            created: 2,
            skipped: 1,
            mode: 'CONSERVATIVE',
          },
        },
      })
      return
    }
    if (path === '/api/v1/devices/dev-1/scan' && method === 'POST') {
      reconciles.push(path)
      await route.fulfill({ json: scanCommand('PENDING') })
      return
    }
    if (path === '/api/v1/sync-commands/scan-1') {
      await route.fulfill({ json: scanCommand('SUCCEEDED') })
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
              held: false,
              applyError: null,
              applyAttempts: null,
              applyErrorPermanent: false,
            },
            {
              deviceId: 'dev-2',
              deviceName: 'Exit',
              connectionState: 'OFFLINE',
              hasGateway: true,
              deviceUserId: '1',
              revision: 1,
              appliedRevision: 3,
              applied: false,
              present: true,
              facePresent: true,
              held: false,
              applyError: 'create failed',
              applyAttempts: 6,
              applyErrorPermanent: false,
            },
            {
              deviceId: 'dev-3',
              deviceName: 'Gym floor',
              connectionState: 'ONLINE',
              hasGateway: true,
              deviceUserId: '4',
              revision: 5,
              appliedRevision: 6,
              applied: false,
              present: true,
              facePresent: true,
              held: true,
              applyError: null,
              applyAttempts: null,
              applyErrorPermanent: false,
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
