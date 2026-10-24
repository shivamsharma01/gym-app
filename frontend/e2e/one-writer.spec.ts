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

test('a reader shows offline while its gym PC is offline', async ({ page }) => {
  await mockStaff(page)
  await page.route('**/api/v1/devices?*', async (route) => {
    await route.fulfill({
      json: {
        content: [device],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
        first: true,
        last: true,
      },
    })
  })
  await page.route('**/api/v1/gateways?*', async (route) => {
    await route.fulfill({
      json: {
        content: [{
          id: 'gw-1',
          name: 'FrontDeskPc',
          status: 'OFFLINE',
          lastHeartbeatAt: '2026-10-23T15:20:00Z',
          lastRegisteredAt: null,
          agentVersion: null,
          createdAt: '2026-10-01T00:00:00Z',
        }],
        page: 0,
        size: 1,
        totalElements: 1,
        totalPages: 1,
        first: true,
        last: true,
      },
    })
  })
  await page.goto('/app/devices')
  await expect(page.getByRole('row').filter({ hasText: 'Entrance' })).toContainText('OFFLINE')
  await expect(page.getByText('FrontDeskPc')).toBeVisible()
})

test('a reader shows its last stored punch and one reconcile action', async ({ page }) => {
  await mockStaff(page)
  await page.goto('/app/devices/dev-1')
  await expect(page.getByRole('heading', { name: 'Entrance' })).toBeVisible()
  await expect(page.getByText(/Last punch stored/)).toBeVisible()
  await expect(page.getByRole('button', { name: /send missing members/i })).toHaveCount(1)
  await expect(page.getByRole('button', { name: 'Send missing members' })).toBeVisible()
})

test('reconciling a reader sends one request and reports what was written', async ({ page }) => {
  const reconciles: string[] = []
  await mockStaff(page, [], user, reconciles)
  await page.goto('/app/devices/dev-1')
  await page.getByRole('button', { name: 'Send missing members' }).click()
  await expect(page.locator('#main-content').getByText(/Sending 2 members to this reader/)).toBeVisible()
  await expect(page.getByLabel('Notifications alt+T').getByText(/Sending 2 members to this reader/)).toBeVisible()
  expect(reconciles).toEqual(['/api/v1/devices/dev-1/reconcile'])
})

test('staff without device sync see no reconcile action', async ({ page }) => {
  await mockStaff(page, [], { ...user, roles: ['STAFF'], permissions: ['DEVICE_VIEW', 'MEMBER_VIEW'] })
  await page.goto('/app/devices/dev-1')
  await expect(page.getByRole('heading', { name: 'Entrance' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Send missing members' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Read this reader' })).toHaveCount(0)
})

test('reading a reader now sends one request and shows the gateway result', async ({ page }) => {
  const requests: string[] = []
  await mockStaff(page, [], user, requests)
  await page.goto('/app/devices/dev-1')
  await page.getByRole('button', { name: 'Read this reader' }).click()
  await expect(page.getByText('Reader list received.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Read this reader' })).toBeEnabled()
  expect(requests).toEqual(['/api/v1/devices/dev-1/scan'])
})

test('importing a reader sends one request and lists who stayed pending', async ({ page }) => {
  const requests: string[] = []
  await mockStaff(page, [], user, requests)
  await page.goto('/app/devices/dev-1')
  await page.getByRole('button', { name: 'Skip likely matches' }).click()
  await expect(page.getByText(/Added 2. Skipped 1, already in the gym/)).toBeVisible()
  await expect(page.getByText('Added 2 members.')).toBeVisible()
  await expect(page.getByText('Id 13: The reader face is not on the server')).toBeVisible()
  expect(requests).toEqual(['/api/v1/devices/dev-1/import-pending?mode=CONSERVATIVE'])
})

test('a failed import tells staff what happened', async ({ page }) => {
  await mockStaff(page)
  await page.route('**/api/v1/devices/dev-1/import-pending**', (route) => route.abort('failed'))
  await page.goto('/app/devices/dev-1')
  await page.getByRole('button', { name: 'Add one member per person' }).click()
  await expect(page.locator('#main-content').getByText('Could not reach the server. Try again.')).toBeVisible()
  await expect(page.getByLabel('Notifications alt+T').getByText('Could not reach the server. Try again.')).toBeVisible()
})

test('staff without device sync cannot import a reader', async ({ page }) => {
  await mockStaff(page, [], { ...user, roles: ['STAFF'], permissions: ['DEVICE_VIEW', 'REVIEW_DECIDE'] })
  await page.goto('/app/devices/dev-1')
  await expect(page.getByRole('heading', { name: 'Entrance' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Skip likely matches' })).toHaveCount(0)
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
    reader: 'Entry',
    createdAt: '2026-10-11T05:00:05Z',
  })
  await page.clock.runFor(16_000)

  await expect(page.locator('tbody tr')).toHaveCount(1)
  await expect(page.locator('tbody tr').first()).toContainText('Asha Shah')
  await expect(page.locator('tbody tr').first()).toContainText('Entry')
})

test('the member list groups plan and account and colours the validity end date', async ({ page }) => {
  await mockStaff(page)
  const readers = [
    { deviceId: 'dev-entry', deviceName: 'Entry', deviceUserId: '9', syncState: 'ok' },
    { deviceId: 'dev-exit', deviceName: 'Exit', deviceUserId: '10', syncState: 'ok' },
  ]
  const content = [
    memberRow('Past', '2019-01-01', '2020-01-15', 'EXPIRED', readers),
    memberRow('Later', '2099-01-01', '2099-06-15', 'PENDING', readers),
    memberRow('Now', '2020-01-01', '2090-06-15', 'ACTIVE', readers),
  ]
  await page.route('**/api/v1/members?**', async (route) => {
    await route.fulfill({
      json: { content, page: 0, size: 25, totalElements: content.length, totalPages: 1, first: true, last: true },
    })
  })
  await page.goto('/app/members')
  await expect(page.getByRole('columnheader', { name: 'Phone' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Entry id' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Exit id' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Plan' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Account' })).toBeVisible()
  await expect(page.getByText('555000')).toHaveCount(0)
  const past = page.getByText('15-Jan-20')
  const later = page.getByText('15-Jun-99')
  const now = page.getByText('15-Jun-90')
  await expect(past).toHaveClass(/text-danger/)
  await expect(later).toHaveClass(/text-sky-400/)
  await expect(now).toHaveClass(/text-ok/)

  const sorted = page.waitForRequest((request) => request.url().includes('sort=reader'))
  await page.getByRole('button', { name: 'Entry id' }).click()
  const url = new URL((await sorted).url())
  expect(url.searchParams.get('deviceId')).toBe('dev-entry')
  expect(url.searchParams.get('direction')).toBe('asc')
})

function memberRow(
  name: string,
  validFrom: string,
  validTo: string,
  coverageStatus: string,
  readers: { deviceId: string; deviceName: string; deviceUserId: string; syncState: string }[],
) {
  return {
    id: name,
    memberCode: name,
    firstName: name,
    lastName: 'Shah',
    fullName: `${name} Shah`,
    email: null,
    phone: '555000',
    status: 'ACTIVE',
    deviceAuthority: 'USER',
    coverageStatus,
    validFrom,
    validTo,
    createdAt: '2026-10-09T00:00:00Z',
    readers,
  }
}

test('a validity difference is shown for review and neither side is applied automatically', async ({ page }) => {
  await mockStaff(page)
  await page.goto('/app/review')
  await expect(page.getByRole('heading', { name: 'Review' })).toBeVisible()
  await expect(page.getByText('2026-10-08 – 2026-11-15')).toBeVisible()
  await expect(page.getByText('2026-10-01T00:00:00+05:30 – 2026-10-31T23:59:59+05:30')).toHaveCount(2)
  await expect(page.getByRole('button', { name: 'Use the reader' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Use the member record' })).toBeVisible()
})

test('a member page offers send again and remove for each reader', async ({ page }) => {
  await mockStaff(page)
  await page.goto('/app/members/mem-1')
  await expect(page.getByRole('heading', { name: 'Asha Shah' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Update this reader' })).toHaveCount(3)
  await expect(page.getByRole('button', { name: 'Remove from reader' })).toHaveCount(0)
})

test('delete removes the member from the gym and every reader', async ({ page }) => {
  const hits: string[] = []
  await mockStaff(page, [], { ...user, permissions: [...user.permissions, 'MEMBER_DELETE'] }, hits)
  await page.goto('/app/members/mem-1')
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await page.getByRole('button', { name: 'Delete member' }).click()
  await expect.poll(() => hits).toContain('/api/v1/members/mem-1/permanent')
  await expect(page.getByRole('heading', { name: 'Members' })).toBeVisible()
})

test('audit actor and action filters can be combined', async ({ page }) => {
  await mockStaff(page, [], { ...user, permissions: [...user.permissions, 'AUDIT_VIEW'] })
  await page.goto('/app/audit')
  await expect(page.getByText('MEMBER_CREATED')).toBeVisible()
  await expect(page.getByText('LOGIN_SUCCESS')).toBeVisible()

  await page.getByRole('button', { name: 'Actor', exact: true }).click()
  await page.getByRole('checkbox', { name: 'gym-admin' }).check()
  await page.getByRole('button', { name: 'Actor (1)' }).click()
  await expect(page.getByText('MEMBER_CREATED')).toBeVisible()
  await expect(page.getByText('LOGIN_SUCCESS')).toHaveCount(0)

  await page.getByRole('button', { name: 'Action', exact: true }).click()
  await page.getByRole('checkbox', { name: 'LOGIN_SUCCESS' }).check()
  await page.getByRole('button', { name: 'Action (1)' }).click()
  await expect(page.getByText('No matching events')).toBeVisible()

  await page.getByRole('button', { name: 'Clear filters' }).click()
  await expect(page.getByText('MEMBER_CREATED')).toBeVisible()
  await expect(page.getByText('LOGIN_SUCCESS')).toBeVisible()

  await page.getByLabel('From').fill('2026-10-12')
  await expect(page.getByText('No matching events')).toBeVisible()
  await page.getByRole('button', { name: 'Clear filters' }).click()
  await expect(page.getByText('MEMBER_CREATED')).toBeVisible()
})

test('a member page shows each reader as applied, not applied, or held, from that row only', async ({ page }) => {
  await mockStaff(page)
  await page.goto('/app/members/mem-1')
  await expect(page.getByText('On reader')).toBeVisible()
  await expect(page.getByText('Confirmed on the reader')).toBeVisible()
  await expect(page.getByText('Update failed', { exact: true })).toBeVisible()
  await expect(page.getByText('Still waiting after 6 tries: create failed. It will keep trying.')).toBeVisible()
  await expect(page.getByText('Needs review')).toBeVisible()
  await expect(page.getByText(/Paused. This reader shows someone else at this id/)).toBeVisible()
  await expect(page.getByText(/reader applied/)).toHaveCount(0)
  await expect(page.getByText('Monthly')).toBeVisible()
})

test('creating a member sends one request with a face and no reader choice', async ({ page }) => {
  const created: { contentType: string; body: string }[] = []
  await mockStaff(page, created)
  await page.goto('/app/members/new')
  await expect(page.getByRole('heading', { name: 'New member' })).toBeVisible()
  await expect(page.getByText('Select a reader')).toHaveCount(0)
  await expect(page.getByText('Date of birth')).toHaveCount(0)
  await expect(page.getByText('Serial number')).toHaveCount(0)

  await page.locator('input[name="firstName"]').fill('Asha')
  await page.getByRole('button', { name: 'Save member' }).click()
  await expect(page.locator('#main-content').getByText('A face photo is required')).toBeVisible()
  await expect(page.getByLabel('Notifications alt+T').getByText('A face photo is required')).toBeVisible()
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
  expect(created[0].body).not.toContain('serialNumber')
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
              validFrom: '2026-01-01T00:00:00+05:30',
              validTo: '2027-01-01T23:59:59+05:30',
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
              validFrom: '2026-01-01T00:00:00+05:30',
              validTo: '2027-01-01T23:59:59+05:30',
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
              validFrom: '2026-01-01T00:00:00+05:30',
              validTo: '2027-01-01T23:59:59+05:30',
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
    if (path === '/api/v1/members' && method === 'GET') {
      await route.fulfill({
        json: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, first: true, last: true },
      })
      return
    }
    if (path === '/api/v1/members/mem-1/permanent' && method === 'DELETE') {
      reconciles.push(path)
      await route.fulfill({ status: 204, body: '' })
      return
    }
    if (path === '/api/v1/audit-logs/options') {
      await route.fulfill({
        json: { actors: ['desk', 'gym-admin'], actions: ['LOGIN_SUCCESS', 'MEMBER_CREATED'] },
      })
      return
    }
    if (path === '/api/v1/audit-logs') {
      const selectedActors = url.searchParams.getAll('actor')
      const selectedActions = url.searchParams.getAll('action')
      const from = url.searchParams.get('from')
      const to = url.searchParams.get('to')
      const rows = [
        {
          id: 'a1',
          action: 'MEMBER_CREATED',
          result: 'SUCCESS',
          resourceType: 'Member',
          resourceId: 'mem-1',
          resourceLabel: 'Asha Shah',
          actorUsername: 'gym-admin',
          correlationId: null,
          details: null,
          createdAt: '2026-10-11T05:00:00Z',
        },
        {
          id: 'a2',
          action: 'LOGIN_SUCCESS',
          result: 'SUCCESS',
          resourceType: 'AUTH',
          resourceId: null,
          resourceLabel: null,
          actorUsername: 'desk',
          correlationId: null,
          details: null,
          createdAt: '2026-10-11T04:00:00Z',
        },
      ].filter((row) => selectedActors.length === 0 || selectedActors.includes(row.actorUsername))
        .filter((row) => selectedActions.length === 0 || selectedActions.includes(row.action))
        .filter((row) => !from || row.createdAt.slice(0, 10) >= from)
        .filter((row) => !to || row.createdAt.slice(0, 10) <= to)
      await route.fulfill({
        json: {
          content: rows,
          page: 0,
          size: 25,
          totalElements: rows.length,
          totalPages: 1,
          first: true,
          last: true,
        },
      })
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
