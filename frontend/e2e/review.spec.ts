import { expect, test } from '@playwright/test'

const user = {
  id: 'u1',
  username: 'v1-admin',
  email: 'v1-admin@gym.local',
  fullName: 'Test v1-admin',
  tenantId: 't1',
  roles: ['GYM_ADMIN'],
  permissions: ['DEVICE_VIEW', 'DEVICE_MANAGE', 'REVIEW_DECIDE'],
}

const staff = {
  ...user,
  id: 'u2',
  username: 'v17-staff',
  email: 'v17-staff@gym.local',
  fullName: 'Test v17-staff',
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
      await route.fulfill({ json: [accepted ? { ...openItem, decision: 'ACCEPT_SERVER', actor: 'v1-admin', priorState: 'Left', chosenState: 'Asha Shah', revision: 2 } : openItem] })
      return
    }
    if (path === '/api/v1/reviews/review-1/accept-server' && method === 'POST') {
      accepted = true
      await route.fulfill({
        json: {
          ...openItem,
          decision: 'ACCEPT_SERVER',
          actor: 'v1-admin',
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

test('bootstrap report lists the roster and does not import users', async ({ page }) => {
  const requested: string[] = []
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    requested.push(method + ' ' + path)
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
  expect(requested.some((call) => call.includes('import-users'))).toBe(false)
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
      await route.fulfill({ json: [accepted ? { ...openItem, decision: 'ACCEPT_SERVER', actor: 'v17-staff' } : openItem] })
      return
    }
    if (path === '/api/v1/reviews/review-1/accept-server' && method === 'POST') {
      accepted = true
      await route.fulfill({ json: { ...openItem, decision: 'ACCEPT_SERVER', actor: 'v17-staff' } })
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
