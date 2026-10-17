import path from 'node:path'
import { fileURLToPath } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const root = path.dirname(fileURLToPath(import.meta.url))

/** Playwright mocked E2E has no Spring Boot; skip `/live` so preview does not spam ECONNREFUSED. */
const e2eNoBackend = process.env.E2E === '1' || process.env.CI === 'true'

/** Same-origin proxies for local `vite` / `vite preview` (Playwright). Not used in production. */
const backendProxy = {
  '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true },
  ...(e2eNoBackend
    ? {}
    : { '/live': { target: 'http://127.0.0.1:8080', ws: true } }),
  '/actuator': { target: 'http://127.0.0.1:8080', changeOrigin: true },
}

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': path.resolve(root, 'src') },
  },
  server: {
    port: 5173,
    proxy: { ...backendProxy },
  },
  preview: {
    port: 4173,
    proxy: { ...backendProxy },
  },
})
