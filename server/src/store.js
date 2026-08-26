'use strict';

/**
 * Store backend selector.
 *
 * `./memoryStore.js` (in-process Map + periodic JSON snapshot) and
 * `./redisStore.js` (Redis-backed, safe for more than one relay-signaling
 * process behind a load balancer) expose byte-for-byte the same async
 * method surface — see memoryStore.js's header for the room/device model
 * both implement. `server.js` and `src/signaling.js` talk only to this
 * selector, so they never branch on which backend is live.
 *
 * No Redis configured        → memory store (matches pre-Redis behaviour).
 * REDIS_SOCKET_PATH or _URL  → Redis store. Redis is opt-in, never required to boot.
 *
 * A warning about "more than one process", because the store alone is not
 * enough: Socket.IO's default adapter also keeps its room table in per-process
 * memory. Sharing the *store* across workers without also sharing the
 * *adapter* produces a relay where both devices authenticate happily and every
 * message vanishes. `src/signaling.js` attaches the Redis adapter whenever
 * Redis is configured, which is what actually makes multi-process safe — running
 * the memory store under a multi-worker server (LiteSpeed/Passenger will do
 * this on its own) is never safe, no matter how the app is deployed.
 */

const { redisEnabled } = require('./redisOptions');

module.exports = redisEnabled() ? require('./redisStore') : require('./memoryStore');
