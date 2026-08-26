'use strict';

/**
 * Relay signaling server — entry point.
 *
 *   HTTP : pairing lifecycle (create / join / confirm), ICE issuance, health
 *   WS   : opaque E2EE envelope relay + WebRTC signaling (src/signaling.js)
 *
 * One gateway, many receivers. The server carries two ephemeral public keys
 * during pairing and opaque ciphertext thereafter; it never holds a root key.
 */

const fs = require('fs');
const http = require('http');
const https = require('https');

const express = require('express');
const helmet = require('helmet');
const cors = require('cors');
const compression = require('compression');
const rateLimit = require('express-rate-limit');
const pinoHttp = require('pino-http');

const config = require('./src/config');
const logger = require('./src/logger');
const store = require('./src/store');
const fcm = require('./src/fcm');
const { buildIceServers } = require('./src/turn');
const { attachSignaling } = require('./src/signaling');
const {
  ROLES,
  issueDeviceToken,
  requireBootstrapSecret,
  requireDeviceToken,
  requireGateway,
} = require('./src/auth');
const {
  generatePairCode,
  normalizePairCode,
  generateDeviceId,
  fingerprint,
} = require('./src/crypto');

// ── App ───────────────────────────────────────────────────────────────────────

const app = express();
app.disable('x-powered-by');
app.set('trust proxy', 1);

app.use(helmet({ contentSecurityPolicy: false, crossOriginResourcePolicy: false }));
app.use(cors({ origin: false }));
app.use(compression());
app.use(express.json({ limit: '64kb' }));
app.use(pinoHttp({ logger, autoLogging: { ignore: (req) => req.url === '/health' } }));

const pairLimiter = rateLimit({
  windowMs: 10 * 60 * 1000,
  limit: 30,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  message: { error: 'rate_limited' },
});

const iceLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 30,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  message: { error: 'rate_limited' },
});

// ── Async plumbing ────────────────────────────────────────────────────────────

/**
 * The store is async everywhere (a Redis round-trip in production), but
 * Express 4 does not forward a rejected promise from a route handler to the
 * error middleware on its own. Wrapping every store-touching handler here
 * closes that gap without repeating try/catch in each one.
 */
function asyncHandler(fn) {
  return (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);
}

// ── Health ────────────────────────────────────────────────────────────────────

app.get('/health', asyncHandler(async (_req, res) => {
  res.json({
    ok: true,
    uptimeSec: Math.round(process.uptime()),
    fcm: fcm.isEnabled(),
    turn: config.ice.turnUrls.length > 0,
    ...(await store.stats()),
  });
}));

// ── Pairing: gateway ──────────────────────────────────────────────────────────

/**
 * POST /pair/create
 * Headers: x-bootstrap-secret
 * Body: { deviceId?, model?, label?, fcmToken?, pubKey }
 * → { pairCode, roomId, deviceId, token, expiresAt, ttlSeconds, iceServers }
 *
 * Creates the room and mints the gateway's own token immediately, so the
 * gateway never has to poll for it the way the old 1:1 flow did.
 */
app.post('/pair/create', pairLimiter, requireBootstrapSecret, asyncHandler(async (req, res) => {
  const pubKey = sanitizeKey(req.body?.pubKey);
  if (!pubKey) return res.status(400).json({ error: 'invalid_public_key' });

  const deviceId = sanitizeId(req.body?.deviceId) || generateDeviceId();
  const pairCode = generatePairCode();

  const { roomId, expiresAt } = await store.createRoom({
    pairCode,
    deviceId,
    model: sanitizeText(req.body?.model, 48),
    label: sanitizeText(req.body?.label, 48),
    fcmToken: sanitizeToken(req.body?.fcmToken),
    pubKey,
  });

  const { token } = issueDeviceToken({ deviceId, role: ROLES.GATEWAY, roomId });

  return res.status(201).json({
    pairCode,
    roomId,
    deviceId,
    token,
    expiresAt,
    ttlSeconds: config.auth.pairCodeTtlSeconds,
    ...buildIceServers(roomId),
  });
}));

/**
 * POST /pair/new-code   (gateway token)
 * Body: { pubKey }
 *
 * Rotates the ephemeral key and issues a fresh code so another receiver can be
 * added later without disturbing the ones already paired.
 */
app.post('/pair/new-code', pairLimiter, requireDeviceToken, requireGateway, asyncHandler(async (req, res) => {
  const pubKey = sanitizeKey(req.body?.pubKey);
  if (!pubKey) return res.status(400).json({ error: 'invalid_public_key' });

  const pairCode = generatePairCode();
  const result = await store.issueCode(req.device.roomId, pairCode, pubKey);
  if (!result) return res.status(404).json({ error: 'unknown_room' });

  return res.json({
    pairCode,
    roomId: req.device.roomId,
    deviceId: req.device.deviceId,
    token: '',
    expiresAt: result.expiresAt,
    ttlSeconds: config.auth.pairCodeTtlSeconds,
    ...buildIceServers(req.device.roomId),
  });
}));

/** POST /pair/revoke-code (gateway token) — stop accepting new receivers. */
app.post('/pair/revoke-code', requireDeviceToken, requireGateway, asyncHandler(async (req, res) => {
  await store.revokeCode(req.device.roomId);
  res.status(204).end();
}));

/**
 * GET /pair/pending  (gateway token)
 * → { pending: [{ deviceId, pubKey, model, label, joinedAt }] }
 *
 * The gateway polls this while its pairing screen is open, derives the shared
 * key for each new receiver, and shows the SAS for the user to compare.
 */
app.get('/pair/pending', requireDeviceToken, requireGateway, asyncHandler(async (req, res) => {
  res.json({ pending: await store.pendingJoins(req.device.roomId) });
}));

/** POST /pair/confirm (gateway token) Body: { deviceId } — SAS matched. */
app.post('/pair/confirm', requireDeviceToken, requireGateway, asyncHandler(async (req, res) => {
  const deviceId = sanitizeId(req.body?.deviceId);
  if (!deviceId) return res.status(400).json({ error: 'invalid_device_id' });
  const ok = await store.confirmReceiver(req.device.roomId, deviceId);
  res.status(ok ? 204 : 404).end();
}));

// ── Pairing: receiver ─────────────────────────────────────────────────────────

/**
 * POST /pair/join
 * Body: { pairCode, deviceId?, model?, label?, fcmToken?, pubKey }
 * → { token, roomId, deviceId, gatewayDeviceId, gatewayPubKey, iceServers }
 *
 * The code stays valid until it expires or is revoked, so several receivers can
 * redeem the same one — which is the point of "one code, many phones".
 */
app.post('/pair/join', pairLimiter, asyncHandler(async (req, res) => {
  const pairCode = normalizePairCode(req.body?.pairCode);
  if (!pairCode || pairCode.length < 6) {
    return res.status(400).json({ error: 'invalid_pair_code' });
  }

  const pubKey = sanitizeKey(req.body?.pubKey);
  if (!pubKey) return res.status(400).json({ error: 'invalid_public_key' });

  const deviceId = sanitizeId(req.body?.deviceId) || generateDeviceId();

  const result = await store.joinRoom({
    pairCode,
    deviceId,
    model: sanitizeText(req.body?.model, 48),
    label: sanitizeText(req.body?.label, 48),
    fcmToken: sanitizeToken(req.body?.fcmToken),
    pubKey,
  });

  if (!result.ok) {
    const status =
      result.reason === 'unknown_code' ? 404 : result.reason === 'room_full' ? 409 : 410;
    return res.status(status).json({ error: result.reason });
  }

  const { room } = result;
  const { token, expiresAt } = issueDeviceToken({
    deviceId,
    role: ROLES.RECEIVER,
    roomId: room.roomId,
  });

  return res.json({
    token,
    expiresAt,
    roomId: room.roomId,
    deviceId,
    gatewayDeviceId: room.gateway.deviceId,
    gatewayPubKey: room.gateway.pubKey,
    gatewayModel: room.gateway.model || '',
    gatewayLabel: room.gateway.label || '',
    ...buildIceServers(room.roomId),
  });
}));

// ── Session ───────────────────────────────────────────────────────────────────

app.get('/ice', iceLimiter, requireDeviceToken, (req, res) => {
  res.json(buildIceServers(req.device.roomId));
});

app.post('/fcm/token', requireDeviceToken, asyncHandler(async (req, res) => {
  const token = sanitizeToken(req.body?.token);
  if (!token) return res.status(400).json({ error: 'invalid_token' });
  const ok = await store.setFcmToken(req.device.roomId, req.device.deviceId, token);
  return res.status(ok ? 204 : 404).end();
}));

app.get('/session', requireDeviceToken, asyncHandler(async (req, res) => {
  const { roomId, deviceId, role } = req.device;
  if (!(await store.getRoom(roomId))) return res.status(404).json({ error: 'unknown_room' });

  return res.json({
    roomId,
    role,
    peers: await store.peersOf(roomId, deviceId),
    queuedForMe: await store.queueDepth(roomId, deviceId),
    serverTime: Date.now(),
  });
}));

/**
 * POST /session/remove  Body: { deviceId }
 * A gateway may remove any receiver. A receiver may only remove itself.
 */
app.post('/session/remove', requireDeviceToken, asyncHandler(async (req, res) => {
  const target = sanitizeId(req.body?.deviceId);
  if (!target) return res.status(400).json({ error: 'invalid_device_id' });

  const isSelf = target === req.device.deviceId;
  if (!isSelf && req.device.role !== ROLES.GATEWAY) {
    return res.status(403).json({ error: 'forbidden' });
  }

  const removed = await store.removeDevice(req.device.roomId, target);
  return res.status(removed ? 204 : 404).end();
}));

/** DELETE /session — gateway destroys the room; a receiver only leaves it. */
app.delete('/session', requireDeviceToken, asyncHandler(async (req, res) => {
  if (req.device.role !== ROLES.GATEWAY) {
    const removed = await store.removeDevice(req.device.roomId, req.device.deviceId);
    return res.status(removed ? 204 : 404).end();
  }
  const destroyed = await store.destroyRoom(req.device.roomId);
  logger.warn({ room: fingerprint(req.device.roomId) }, 'room destroyed by gateway');
  return res.status(destroyed ? 204 : 404).end();
}));

// ── Errors ────────────────────────────────────────────────────────────────────

app.use((_req, res) => res.status(404).json({ error: 'not_found' }));
// eslint-disable-next-line no-unused-vars
app.use((err, _req, res, _next) => {
  logger.error({ err: err.message }, 'unhandled request error');
  res.status(500).json({ error: 'internal_error' });
});

// ── Boot ──────────────────────────────────────────────────────────────────────

function createServer() {
  if (config.server.tlsCertPath && config.server.tlsKeyPath) {
    return https.createServer(
      {
        cert: fs.readFileSync(config.server.tlsCertPath),
        key: fs.readFileSync(config.server.tlsKeyPath),
        minVersion: 'TLSv1.2',
      },
      app,
    );
  }
  return http.createServer(app);
}

fcm.init();

const server = createServer();
attachSignaling(server);

const sweep = setInterval(() => {
  store.expireCodes().catch((err) => logger.error({ err: err.message }, 'expireCodes failed'));
}, 60_000);
sweep.unref?.();

server.listen(config.server.port, config.server.host, () => {
  logger.info(
    {
      port: config.server.port,
      tls: Boolean(config.server.tlsCertPath),
      turn: config.ice.turnUrls.length,
      fcm: fcm.isEnabled(),
      maxReceivers: config.relay.maxReceiversPerRoom,
    },
    'relay signaling server listening',
  );
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    logger.info({ signal }, 'shutting down');
    store
      .flush()
      .catch((err) => logger.error({ err: err.message }, 'flush on shutdown failed'))
      .finally(() => {
        server.close(() => process.exit(0));
        setTimeout(() => process.exit(1), 8000).unref();
      });
  });
}

process.on('unhandledRejection', (reason) =>
  logger.error({ reason: String(reason) }, 'unhandled rejection'));

process.on('uncaughtException', (err) => {
  logger.fatal({ err: err.message, stack: err.stack }, 'uncaught exception');
  store
    .flush()
    .catch(() => {})
    .finally(() => process.exit(1));
});

// ── Input hygiene ─────────────────────────────────────────────────────────────

/** Truncate to `max` and strip C0/C1 control characters. */
function sanitizeText(value, max) {
  if (typeof value !== 'string') return '';
  // eslint-disable-next-line no-control-regex
  return value.slice(0, max).replace(/[ --]/g, '');
}

function sanitizeId(value) {
  const s = sanitizeText(value, 64);
  return /^[A-Za-z0-9._:-]{6,64}$/.test(s) ? s : '';
}

function sanitizeToken(value) {
  const s = sanitizeText(value, 512);
  return /^[A-Za-z0-9._:@%/+-]{20,512}$/.test(s) ? s : '';
}

/**
 * A base64url X.509 P-256 public key is 91 bytes → 122 base64url characters.
 * Bounding it here stops a client parking megabytes in room state.
 */
function sanitizeKey(value) {
  const s = sanitizeText(value, 256);
  return /^[A-Za-z0-9_-]{80,256}$/.test(s) ? s : '';
}

module.exports = { app, server };
