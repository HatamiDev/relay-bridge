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
 * REDIS_URL unset → memory store (default, matches pre-Redis behaviour).
 * REDIS_URL set   → Redis store. Redis is opt-in, never mandatory to boot.
 */

const config = require('./config');

module.exports = config.redis.url ? require('./redisStore') : require('./memoryStore');
