/**
 * Lightweight checks for same-origin URL helpers (no test runner required).
 * Run: npm run test:urls
 */
import { apiUrl, buildLiveWsUrl, staffLiveWsProtocols } from './backendUrls.ts'

function assert(cond: unknown, msg: string): asserts cond {
  if (!cond) throw new Error(msg)
}

assert(apiUrl('/api/v1/auth/login') === '/api/v1/auth/login', 'apiUrl should be relative when base unset')
assert(apiUrl('api/v1/x') === '/api/v1/x', 'apiUrl should normalize missing leading slash')

const https = buildLiveWsUrl({ protocol: 'https:', host: 'gym.example.com' })
assert(https === 'wss://gym.example.com/live', `unexpected https ws: ${https}`)
assert(!https.includes('access_token'), 'live url must not carry the token')

const http = buildLiveWsUrl({ protocol: 'http:', host: 'localhost:5173' })
assert(http === 'ws://localhost:5173/live', `unexpected http ws: ${http}`)

const protocols = staffLiveWsProtocols('eyJhbGciOiJ')
assert(protocols[0] === 'bearer' && protocols[1] === 'eyJhbGciOiJ', 'bearer protocol + jwt')

console.log('backendUrls.selftest: ok')
