'use strict';

/**
 * Centralised, validated configuration.
 *
 * Every value the process needs is resolved exactly once here so that no other
 * module reads `process.env` directly. Fatal misconfiguration crashes at boot
 * rather than at the first request.
 */

require('dotenv').config();

const path = require('path');

/** @param {string} name @param {string} [fallback] */
function required(name, fallback) {
  const value = process.env[name] ?? fallback;
  if (value === undefined || value === null || value === '') {
    throw new Error(`[config] Missing required environment variable: ${name}`);
  }
  return value;
}

/** @param {string} name @param {string} fallback */
function optional(name, fallback = '') {
  const value = process.env[name];
  return value === undefined || value === '' ? fallback : value;
}

/** @param {string} name @param {number} fallback */
function int(name, fallback) {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const parsed = Number.parseInt(raw, 10);
  if (Number.isNaN(parsed)) throw new Error(`[config] ${name} must be an integer`);
  return parsed;
}

/** Comma-separated list → trimmed array with empties removed. */
function list(name, fallback = '') {
  return optional(name, fallback)
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

const isProd = optional('NODE_ENV', 'development') === 'production';

const config = Object.freeze({
  isProd,

  server: Object.freeze({
    port: int('PORT', 8443),
    host: optional('HOST', '0.0.0.0'),
    publicOrigin: optional('PUBLIC_ORIGIN', ''),
    tlsCertPath: optional('TLS_CERT_PATH'),
    tlsKeyPath: optional('TLS_KEY_PATH'),
  }),

  auth: Object.freeze({
    jwtSecret: required('JWT_SECRET'),
    jwtTtlSeconds: int('JWT_TTL_SECONDS', 60 * 60 * 24 * 30),
    bootstrapSecret: required('BOOTSTRAP_SECRET'),
    pairCodeTtlSeconds: int('PAIR_CODE_TTL_SECONDS', 300),
  }),

  ice: Object.freeze({
    stunUrls: list('STUN_URLS', 'stun:stun.l.google.com:19302'),
    turnUrls: list('TURN_URLS'),
    turnSecret: optional('TURN_STATIC_AUTH_SECRET'),
    turnTtlSeconds: int('TURN_CREDENTIAL_TTL_SECONDS', 3600),
  }),

  fcm: Object.freeze({
    serviceAccountPath: optional('FCM_SERVICE_ACCOUNT_PATH')
      ? path.resolve(process.cwd(), optional('FCM_SERVICE_ACCOUNT_PATH'))
      : '',
  }),

  relay: Object.freeze({
    /** How many receivers one gateway may serve. */
    maxReceiversPerRoom: int('MAX_RECEIVERS_PER_ROOM', 8),
    offlineQueueMax: int('OFFLINE_QUEUE_MAX', 500),
    maxEnvelopeBytes: int('MAX_ENVELOPE_BYTES', 128 * 1024),
    snapshotPath: path.resolve(process.cwd(), optional('SNAPSHOT_PATH', './data/rooms.json')),
    snapshotIntervalMs: int('SNAPSHOT_INTERVAL_MS', 15_000),
  }),

  // Optional: `./store.js` selects the Redis-backed store (`./redisStore.js`)
  // over the in-process one the moment a Redis endpoint is configured. Leaving
  // both empty keeps the single-node, snapshot-to-disk behaviour — Redis is
  // never required to boot.
  //
  // Two ways to reach Redis, because hosts differ:
  //
  //   REDIS_URL          redis://[:password@]host:port[/db]  — TCP
  //   REDIS_SOCKET_PATH  /home/user/redis/redis.sock         — unix socket
  //
  // Shared cPanel hosting commonly gives a per-account Redis on a unix socket
  // with no TCP listener at all, and a `redis://` URL cannot express a
  // filesystem path — hence the separate variable rather than a URL scheme.
  // When both are set the socket wins: it is the more specific answer.
  //
  // REDIS_DB matters when the Redis instance is shared with another app on the
  // same account. A different database index keeps the two key spaces from
  // ever seeing each other, on top of the `relay:` key prefix.
  redis: Object.freeze({
    url: optional('REDIS_URL', ''),
    socketPath: optional('REDIS_SOCKET_PATH', ''),
    password: optional('REDIS_PASSWORD', ''),
    database: int('REDIS_DB', 0),
    keyPrefix: optional('REDIS_KEY_PREFIX', 'relay:'),
    tlsRejectUnauthorized: optional('REDIS_TLS_REJECT_UNAUTHORIZED', 'true') !== 'false',
  }),

  logLevel: optional('LOG_LEVEL', isProd ? 'info' : 'debug'),
  // Absolute path, or empty for stdout. Needed on any host that discards
  // stdout — LiteSpeed/Passenger keeps stderr only.
  logFile: optional('LOG_FILE', ''),
});

// ── Boot-time sanity checks ───────────────────────────────────────────────────
if (isProd && config.auth.jwtSecret.length < 32) {
  throw new Error('[config] JWT_SECRET must be at least 32 characters in production');
}
if (isProd && config.auth.bootstrapSecret.startsWith('CHANGE_ME')) {
  throw new Error('[config] BOOTSTRAP_SECRET still holds its placeholder value');
}
if (config.ice.turnUrls.length > 0 && !config.ice.turnSecret) {
  throw new Error('[config] TURN_URLS set but TURN_STATIC_AUTH_SECRET is empty');
}

module.exports = config;
