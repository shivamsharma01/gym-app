import { expect, test } from '@playwright/test'

const user = {
  id: 'u1',
  username: 'gym-admin',
  email: 'gym-admin@gym.local',
  fullName: 'Test gym-admin',
  tenantId: 't1',
  roles: ['GYM_ADMIN'],
  permissions: ['DEVICE_VIEW', 'DEVICE_MANAGE', 'REVIEW_DECIDE'],
}

const staff = {
  ...user,
  id: 'u2',
  username: 'front-desk',
  email: 'front-desk@gym.local',
  fullName: 'Test front-desk',
  roles: ['STAFF'],
  permissions: ['DEVICE_VIEW', 'REVIEW_DECIDE'],
}

const openItem = {
  id: 'review-1',
  kind: 'REVIEW',
  deviceId: 'dev-1',
  deviceUserId: '1',
  serverName: 'Asha Shah',
  readerName: 'Left',
  baselineName: 'Asha Shah',
  readerAbsent: false,
  open: true,
  decision: null,
  actor: null,
  priorState: null,
  chosenState: null,
  revision: null,
  verificationError: null,
}

test('accept server records the staff decision', async ({ page }) => {
  let accepted = false
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
    if (path === '/api/v1/reviews' && method === 'GET') {
      await route.fulfill({ json: [accepted ? { ...openItem, decision: 'ACCEPT_SERVER', actor: 'gym-admin', priorState: 'Left', chosenState: 'Asha Shah', revision: 2 } : openItem] })
      return
    }
    if (path === '/api/v1/reviews/review-1/accept-server' && method === 'POST') {
      accepted = true
      await route.fulfill({
        json: {
          ...openItem,
          decision: 'ACCEPT_SERVER',
          actor: 'gym-admin',
          priorState: 'Left',
          chosenState: 'Asha Shah',
          revision: 2,
        },
      })
      return
    }
    await route.fulfill({ status: 404, json: {} })
  })

  await page.goto('/app/review')
  await expect(page.getByRole('heading', { name: 'Review' })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'Asha Shah' }).first()).toBeVisible()
  await expect(page.getByRole('cell', { name: 'Left' })).toBeVisible()
  await page.getByRole('button', { name: 'Accept server' }).click()
  await expect(page.getByText('ACCEPT_SERVER')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Link all' })).toHaveCount(0)
})

test('bootstrap report lists the roster and offers no bulk link', async ({ page }) => {
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
    if (path === '/api/v1/reviews' && method === 'GET') {
      await route.fulfill({ json: [] })
      return
    }
    if (path === '/api/v1/reviews/bootstrap' && method === 'POST') {
      await route.fulfill({
        json: {
          runId: 'run-15',
          rows: [
            {
              outcome: 'UNLINKED',
              deviceId: 'dev-1',
              deviceUserId: '7',
              memberId: null,
              readerName: 'Walk In',
              serverName: null,
              suggestionMemberId: null,
            },
          ],
        },
      })
      return
    }
    await route.fulfill({ status: 404, json: {} })
  })

  await page.goto('/app/review')
  await page.getByRole('button', { name: 'Bootstrap report' }).click()
  await expect(page.getByRole('cell', { name: 'UNLINKED' })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'Walk In' })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'run-15' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Link all' })).toHaveCount(0)
})

test('staff can decide a review item but cannot run bootstrap', async ({ page }) => {
  let accepted = false
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    if (path === '/api/v1/auth/refresh' && method === 'POST') {
      await route.fulfill({
        json: { accessToken: 'token', tokenType: 'Bearer', expiresInSeconds: 900, user: staff },
      })
      return
    }
    if (path === '/api/v1/me') {
      await route.fulfill({ json: staff })
      return
    }
    if (path === '/api/v1/settings') {
      await route.fulfill({ json: {} })
      return
    }
    if (path === '/api/v1/reviews' && method === 'GET') {
      await route.fulfill({ json: [accepted ? { ...openItem, decision: 'ACCEPT_SERVER', actor: 'front-desk' } : openItem] })
      return
    }
    if (path === '/api/v1/reviews/review-1/accept-server' && method === 'POST') {
      accepted = true
      await route.fulfill({ json: { ...openItem, decision: 'ACCEPT_SERVER', actor: 'front-desk' } })
      return
    }
    await route.fulfill({ status: 404, json: {} })
  })

  await page.goto('/app/review')
  await expect(page.getByRole('heading', { name: 'Review' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Bootstrap report' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Accept server' }).click()
  await expect(page.getByText('ACCEPT_SERVER')).toBeVisible()
})

async function routeReviews(page: import('@playwright/test').Page, items: unknown[], report?: unknown) {
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
    if (path === '/api/v1/reviews' && method === 'GET') {
      await route.fulfill({ json: items })
      return
    }
    if (path === '/api/v1/reviews/bootstrap' && method === 'POST' && report) {
      await route.fulfill({ json: report })
      return
    }
    await route.fulfill({ status: 404, json: {} })
  })
}

test('a reader-side disable and photo change are shown against the server', async ({ page }) => {
  await routeReviews(page, [
    {
      ...openItem,
      readerName: 'Asha Shah',
      serverUserStatus: 0,
      readerUserStatus: 1,
      baselineUserStatus: 0,
      readerPhotoDiffers: true,
    },
  ])

  await page.goto('/app/review')
  await expect(page.getByText('Disabled')).toBeVisible()
  await expect(page.getByText('Enabled')).toHaveCount(2)
  await expect(page.getByText('Photo differs from the server photo')).toBeVisible()
})

test('a suspended member offers restore under a new id or keep off, and no accept server', async ({ page }) => {
  await routeReviews(page, [{ ...openItem, kind: 'SUSPENDED', readerName: 'Bob Stranger' }])

  await page.goto('/app/review')
  await expect(page.getByText('Someone else holds this id; the member is off this reader')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Restore under a new id' })).toBeEnabled()
  await expect(page.getByRole('button', { name: 'Keep off this reader' })).toBeEnabled()
  await expect(page.getByRole('button', { name: 'Accept server' })).toHaveCount(0)
})

test('one member items on two readers are grouped under the member and decided per reader', async ({ page }) => {
  await routeReviews(page, [
    { ...openItem, id: 'review-entrance', deviceName: 'Entrance', memberId: 'member-asha', memberName: 'Asha Shah' },
    {
      ...openItem,
      id: 'review-exit',
      deviceId: 'dev-2',
      deviceUserId: '4',
      readerName: 'Asha S',
      deviceName: 'Exit',
      memberId: 'member-asha',
      memberName: 'Asha Shah',
    },
    {
      ...openItem,
      id: 'enrollment-9',
      kind: 'ENROLLMENT',
      deviceId: 'dev-2',
      deviceUserId: '9',
      serverName: null,
      readerName: 'asha shah',
      deviceName: 'Exit',
      possibleMemberId: 'member-asha',
      possibleMemberName: 'Asha Shah',
    },
  ])

  await page.goto('/app/review')
  const member = page.getByRole('rowgroup', { name: 'Member: Asha Shah' })
  await expect(member).toHaveCount(1)
  await expect(member.getByRole('row')).toHaveCount(3)
  await expect(member.getByRole('cell', { name: 'Entrance' })).toBeVisible()
  await expect(member.getByRole('cell', { name: 'Exit' })).toBeVisible()
  await expect(member.getByRole('button', { name: 'Accept server' })).toHaveCount(2)

  const unknown = page.getByRole('rowgroup', { name: 'People the server does not know' })
  await expect(unknown.getByText('May already be Asha Shah; link instead of creating')).toBeVisible()
  await expect(page.getByLabel('Member id for 9')).toHaveValue('member-asha')
})

test('the bootstrap suggestion fills in the member to link', async ({ page }) => {
  await routeReviews(
    page,
    [{ ...openItem, id: 'enrollment-7', kind: 'ENROLLMENT', deviceUserId: '7', serverName: null, readerName: 'Asha Rao' }],
    {
      runId: 'run-16',
      rows: [
        {
          outcome: 'UNLINKED',
          deviceId: 'dev-1',
          deviceUserId: '7',
          memberId: null,
          readerName: 'Asha Rao',
          serverName: null,
          suggestionMemberId: 'member-asha',
        },
      ],
    },
  )

  await page.goto('/app/review')
  await expect(page.getByLabel('Member id for 7')).toHaveValue('')
  await page.getByRole('button', { name: 'Bootstrap report' }).click()
  await expect(page.getByLabel('Member id for 7')).toHaveValue('member-asha')
  await expect(page.getByRole('button', { name: 'Link', exact: true })).toBeEnabled()
})
