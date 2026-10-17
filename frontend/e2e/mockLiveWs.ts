import type { Page } from '@playwright/test'

/**
 * Staff UI opens `/live` after login. Mocked E2E has no Spring Boot on :8080, so without this
 * Vite's preview proxy logs `ws proxy error: ECONNREFUSED` on every test.
 */
export async function mockStaffLiveWs(page: Page) {
  await page.routeWebSocket(/\/live(?:\?|$)/, () => {
    // Accept the client socket; do not bridge to the Vite → :8080 proxy.
  })
}
