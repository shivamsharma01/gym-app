import path from 'node:path'
import { fileURLToPath } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const root = path.dirname(fileURLToPath(import.meta.url))

/** Same-origin proxies for local `vite` / `vite preview` (Playwright). Not used in production. */
const backendProxy = {
  '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true },
  '/live': { target: 'http://127.0.0.1:8080', ws: true },
  '/actuator': { target: 'http://127.0.0.1:8080', changeOrigin: true },
} as const

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
