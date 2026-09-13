import { expect, test } from '@playwright/test'

test.describe('Public platform landing', () => {
  test('shows staff sign-in and main landmark', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByRole('heading', { name: 'Gym platform' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Staff sign in' })).toBeVisible()
    await expect(page.getByRole('main')).toBeVisible()
  })
})

test.describe('Staff login page', () => {
  test('exposes form labels and skip-friendly structure', async ({ page }) => {
    await page.goto('/app/login')
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()
    await expect(page.getByLabel('Username or email')).toBeVisible()
    await expect(page.getByLabel('Password')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Continue' })).toBeVisible()
  })

  test('shows an alert on bad credentials when API is available', async ({ page }) => {
    test.skip(process.env.E2E_API !== '1', 'Set E2E_API=1 with a running backend to enable')
    await page.goto('/app/login')
    await page.getByLabel('Username or email').fill('nobody')
    await page.getByLabel('Password').fill('wrong-password')
    await page.getByRole('button', { name: 'Continue' }).click()
    await expect(page.getByRole('alert')).toBeVisible({ timeout: 10_000 })
  })
})
