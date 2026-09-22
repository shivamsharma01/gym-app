/**
 * Lightweight checks for same-origin URL helpers (no test runner required).
 * Run: npm run test:urls
 */
import { apiUrl, buildLiveWsUrl } from './backendUrls.ts'

function assert(cond: unknown, msg: string): asserts cond {
  if (!cond) throw new Error(msg)
}

assert(apiUrl('/api/v1/auth/login') === '/api/v1/auth/login', 'apiUrl should be relative when base unset')
assert(apiUrl('api/v1/x') === '/api/v1/x', 'apiUrl should normalize missing leading slash')

const https = buildLiveWsUrl({ protocol: 'https:', host: 'gym.example.com' }, 'tok a')
assert(
  https === 'wss://gym.example.com/live?access_token=tok%20a',
  `unexpected https ws: ${https}`,
)

const http = buildLiveWsUrl({ protocol: 'http:', host: 'localhost:5173' }, 'abc')
assert(http === 'ws://localhost:5173/live?access_token=abc', `unexpected http ws: ${http}`)

console.log('backendUrls.selftest: ok')
