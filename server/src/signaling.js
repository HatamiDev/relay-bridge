'use strict';

/**
 * Socket.IO relay.
 *
 * The server authenticates a socket, decides which single device an envelope is
 * addressed to (`envelope.dst`), and either delivers it now or queues it and
 * wakes the target over FCM. It never inspects `envelope.ct`.
 *
 * Addressing rules, enforced server-side:
 *   • a receiver may only ever address the gateway
 *   • a gateway may address any confirmed receiver in its own room
 * A receiver therefore cannot reach a sibling receiver even if it tries, and in
 * any case could not encrypt for one — sibling keys differ.
 *
 * The store is async (a Redis round-trip in production), so the handshake
 * middleware, the connection handler, and every per-event listener below are
 * async too. Socket.IO does not await a middleware's return value — it only
 * cares that `next()` eventually fires — so an `async (socket, next) => {}`
 * middleware works exactly like a callback-based one, just with `await`
 * instead of nested callbacks.
 */

const { Server } = require('socket.io');
const config = require('./config');
const logger = require('./logger');
const store = require('./store');
const { redisEnabled, redisClientOptions, redisEndpointLabel } = require('./redisOptions');
const fcm = require('./fcm');
const { verifyDeviceToken } = require('./auth');
const { buildIceServers } = require('./turn');
const { fingerprint } = require('./crypto');

const ROLE_GATEWAY = 'gateway';
const ROLE_RECEIVER = 'receiver';

/** Events a RECEIVER may emit. Always routed to the gateway. */
const RECEIVER_EVENTS = new Set([
  'sms:outbound',
  'sms:sync',
  'contacts:sync',
  'call:place',
  'call:answer',
  'call:reject',
  'call:hangup',
  'call:dtmf',
  'call:mute',
  'rtc:answer',
  'rtc:ice',
  'rtc:renegotiate',
]);

/** Events a GATEWAY may emit. Routed to the addressed receiver. */
const GATEWAY_EVENTS = new Set([
  'sms:inbound',
  'sms:status',
  'sms:sync:result',
  'contacts:result',
  'call:incoming',
  'call:state',
  'call:hangup',
  'rtc:offer',
  'rtc:ice',
  'rtc:renegotiate',
]);

const URGENT_EVENTS = new Set([
  'call:incoming',
  'call:state',
  'rtc:offer',
  'rtc:ice',
  'call:hangup',
]);

const WAKEABLE_EVENTS = new Set([...URGENT_EVENTS, 'sms:inbound', 'sms:status']);

/** Structural validation only — shape and size, never contents. */
function isValidEnvelope(envelope) {
  if (!envelope || typeof envelope !== 'object') return false;
  if (envelope.v !== 2) return false;
  if (typeof envelope.ev !== 'string' || envelope.ev.length > 64) return false;
  if (typeof envelope.dst !== 'string' || envelope.dst.length < 1 || envelope.dst.length > 64) return false;
  if (!Number.isInteger(envelope.sq) || envelope.sq < 0) return false;
  if (!Number.isInteger(envelope.ts)) return false;
  if (typeof envelope.iv !== 'string' || envelope.iv.length < 12 || envelope.iv.length > 32) return false;
  if (typeof envelope.ct !== 'string' || envelope.ct.length === 0) return false;
  if (envelope.ct.length > Math.ceil((config.relay.maxEnvelopeBytes * 4) / 3)) return false;
  return true;
}

/**
 * Make Socket.IO rooms work across processes.
 *
 * Delivery below is `nsp.to(room).emit(...)`. Socket.IO's default adapter keeps
 * its room table in the process's own memory, so with more than one relay
 * process — a load balancer, or LiteSpeed/Passenger spawning several workers
 * for one app — a gateway attached to worker A simply cannot reach a receiver
 * attached to worker B. The pairing succeeds, both sides look healthy, and
 * every message silently goes nowhere.
 *
 * The Redis adapter fixes that by publishing emits over pub/sub so any worker
 * holding the target socket delivers it. It is wired only when REDIS_URL is
 * set — the single-process memory-store deployment has nothing to coordinate.
 *
 * Failure here is deliberately fatal rather than degraded: a relay that has
 * been told it is multi-process, but is silently routing to one process only,
 * is worse than one that refuses to start.
 */
async function attachRedisAdapter(io) {
  if (!redisEnabled()) return false;

  const { createAdapter } = require('@socket.io/redis-adapter');
  const { createClient } = require('redis');

  // The adapter needs its own pair of connections: a client in subscriber mode
  // cannot issue ordinary commands, so it can never be the store's client.
  const pub = createClient(redisClientOptions());
  const sub = pub.duplicate();

  pub.on('error', (err) => logger.error({ err: err.message }, 'redis adapter pub error'));
  sub.on('error', (err) => logger.error({ err: err.message }, 'redis adapter sub error'));

  await Promise.all([pub.connect(), sub.connect()]);
  io.adapter(createAdapter(pub, sub));
  logger.info({ endpoint: redisEndpointLabel() }, 'socket.io redis adapter attached — cross-process delivery enabled');
  return true;
}

function attachSignaling(httpServer) {
  const io = new Server(httpServer, {
    path: '/socket.io',
    serveClient: false,
    pingInterval: 20_000,
    pingTimeout: 25_000,
    connectTimeout: 20_000,
    maxHttpBufferSize: config.relay.maxEnvelopeBytes + 4096,
    transports: ['websocket', 'polling'],
    cors: { origin: false },
  });

  // Fire-and-forget: sockets that connect during the few milliseconds before
  // the adapter attaches are re-registered by it on attach, so there is no gap
  // to guard against.
  attachRedisAdapter(io).catch((err) => {
    logger.error({ err: err.message }, 'redis adapter failed to attach');
    throw err;
  });

  const nsp = io.of('/relay');

  // ── Handshake ──────────────────────────────────────────────────────────────
  nsp.use(async (socket, next) => {
    try {
      const claims = verifyDeviceToken(socket.handshake.auth?.token);
      const claimedRole = socket.handshake.auth?.role;

      if (claimedRole && claimedRole !== claims.role) return next(new Error('role_mismatch'));

      const room = await store.getRoom(claims.roomId);
      if (!room) return next(new Error('unknown_room'));

      const record = await store.getDevice(claims.roomId, claims.deviceId);
      if (!record) return next(new Error('device_not_enrolled'));
      if (record.role !== claims.role) return next(new Error('role_mismatch'));

      socket.data.device = claims;
      return next();
    } catch (err) {
      logger.warn({ err: err.message }, 'socket auth rejected');
      return next(new Error('unauthorized'));
    }
  });

  // ── Connection ─────────────────────────────────────────────────────────────
  nsp.on('connection', (socket) => {
    handleConnection(nsp, socket).catch((err) => {
      logger.error({ err: err.message }, 'connection setup failed');
      socket.disconnect(true);
    });
  });

  return io;
}

/** Everything that used to run synchronously inside 'connection' now awaits the store. */
async function handleConnection(nsp, socket) {
  const { deviceId, role, roomId } = socket.data.device;
  const roomTag = fingerprint(roomId);

  socket.join(deviceKey(roomId, deviceId));

  // Evict a previous socket for the same device (app restart, network flip).
  //
  // `nsp.in(id).disconnectSockets()` rather than `nsp.sockets.get(id)`: the
  // local map only holds sockets on *this* worker, so under a multi-worker
  // server the old socket usually is not there and the stale connection
  // survives — two live sockets for one device, each believing it is current,
  // with delivery landing on whichever the store last recorded. Going through
  // the namespace routes the disconnect via the adapter, so it reaches the
  // worker actually holding the socket. Every socket joins a room named after
  // its own id, which is what makes addressing one by id work at all.
  const existing = await store.getDevice(roomId, deviceId);
  const previous = existing?.socketId;
  if (previous && previous !== socket.id) {
    nsp.in(previous).disconnectSockets(true);
  }

  await store.markOnline(roomId, deviceId, socket.id);
  logger.info({ room: roomTag, role }, 'device connected');

  socket.emit('session:ready', {
    role,
    deviceId,
    peers: await store.peersOf(roomId, deviceId),
    ...buildIceServers(roomId),
    serverTime: Date.now(),
  });

  await broadcastToRoom(nsp, roomId, deviceId, 'peer:presence', {
    deviceId,
    role,
    online: true,
    ts: Date.now(),
  });

  // Flush anything queued while we were away, oldest first.
  const backlog = await store.drain(roomId, deviceId);
  if (backlog.length) {
    logger.info({ room: roomTag, role, count: backlog.length }, 'flushing offline queue');
    for (const item of backlog) socket.emit(item.event, item.envelope);
    socket.emit('queue:flushed', { count: backlog.length });
  }

  // ── Relay ────────────────────────────────────────────────────────────────
  const allowed = role === ROLE_RECEIVER ? RECEIVER_EVENTS : GATEWAY_EVENTS;

  for (const event of allowed) {
    socket.on(event, (envelope, ack) => {
      handleEnvelope({ nsp, socket, roomId, roomTag, deviceId, role, event, envelope, ack }).catch(
        (err) => {
          logger.error({ room: roomTag, event, err: err.message }, 'envelope handling failed');
          if (typeof ack === 'function') ack({ ok: false, error: 'internal_error' });
        },
      );
    });
  }

  // ── Server-handled events ────────────────────────────────────────────────

  socket.on('ice:refresh', (_payload, ack) => {
    const ice = buildIceServers(roomId);
    socket.emit('ice:servers', ice);
    if (typeof ack === 'function') ack({ ok: true, ...ice });
  });

  /** Plain liveness telemetry. No user content by construction. */
  socket.on('presence', (payload, ack) => {
    const safe = {
      deviceId,
      role,
      online: true,
      model: text(payload?.model, 48),
      batteryPct: clampInt(payload?.batteryPct, -1, 100),
      charging: Boolean(payload?.charging),
      signalDbm: clampInt(payload?.signalDbm, -140, 0),
      simState: text(payload?.simState, 24),
      appVersion: text(payload?.appVersion, 24),
      ts: Date.now(),
    };
    broadcastToRoom(nsp, roomId, deviceId, 'peer:presence', safe)
      .then(() => { if (typeof ack === 'function') ack({ ok: true }); })
      .catch((err) => {
        logger.error({ room: roomTag, err: err.message }, 'presence broadcast failed');
        if (typeof ack === 'function') ack({ ok: false, error: 'internal_error' });
      });
  });

  socket.on('rtc:stats', (payload, ack) => {
    logger.debug(
      {
        room: roomTag,
        rtt: clampInt(payload?.rttMs, 0, 10_000),
        jitter: clampInt(payload?.jitterMs, 0, 10_000),
        loss: Number(payload?.lossPct) || 0,
        bitrate: clampInt(payload?.bitrateKbps, 0, 1_000),
        codec: text(payload?.codec, 24),
      },
      'rtc stats',
    );
    if (typeof ack === 'function') ack({ ok: true });
  });

  socket.on('fcm:token', (payload, ack) => {
    const token = typeof payload?.token === 'string' ? payload.token : '';
    store
      .setFcmToken(roomId, deviceId, token)
      .then(() => { if (typeof ack === 'function') ack({ ok: Boolean(token) }); })
      .catch((err) => {
        logger.error({ room: roomTag, err: err.message }, 'fcm token store failed');
        if (typeof ack === 'function') ack({ ok: false });
      });
  });

  socket.on('disconnect', (reason) => {
    store
      .markOffline(roomId, deviceId, socket.id)
      .then(() =>
        broadcastToRoom(nsp, roomId, deviceId, 'peer:presence', {
          deviceId,
          role,
          online: false,
          ts: Date.now(),
        }),
      )
      .then(() => logger.info({ room: roomTag, role, reason }, 'device disconnected'))
      .catch((err) => logger.error({ room: roomTag, err: err.message }, 'disconnect handling failed'));
  });
}

/** Body of the per-event envelope relay listener, split out so it can be awaited and caught. */
async function handleEnvelope({ nsp, roomId, roomTag, deviceId, role, event, envelope, ack }) {
  const reply = (payload) => { if (typeof ack === 'function') ack(payload); };

  if (!isValidEnvelope(envelope)) {
    logger.warn({ room: roomTag, role, event }, 'malformed envelope dropped');
    return reply({ ok: false, error: 'malformed_envelope' });
  }
  // The event name is bound into the AAD, so a mismatch means someone is
  // trying to re-label a captured envelope.
  if (envelope.ev !== event) {
    logger.warn({ room: roomTag, event, ev: envelope.ev }, 'event/AAD mismatch dropped');
    return reply({ ok: false, error: 'event_mismatch' });
  }

  // ── Addressing policy ────────────────────────────────────────────────
  let dst;
  if (role === ROLE_RECEIVER) {
    const gateway = await store.gatewayOf(roomId);
    if (!gateway) return reply({ ok: false, error: 'no_gateway' });
    if (envelope.dst !== gateway.deviceId) {
      logger.warn({ room: roomTag }, 'receiver addressed a non-gateway; dropped');
      return reply({ ok: false, error: 'bad_destination' });
    }
    dst = gateway.deviceId;
  } else {
    const target = await store.getDevice(roomId, envelope.dst);
    if (!target || target.role !== ROLE_RECEIVER) {
      return reply({ ok: false, error: 'unknown_destination' });
    }
    dst = target.deviceId;
  }

  const delivered = await deliver({ nsp, roomId, dst, src: deviceId, event, envelope });
  return reply({ ok: true, delivered, ts: Date.now() });
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function deviceKey(roomId, deviceId) {
  return `${roomId}:${deviceId}`;
}

/** Emit to one device. */
function emitTo(nsp, roomId, deviceId, event, payload) {
  nsp.to(deviceKey(roomId, deviceId)).emit(event, payload);
}

/** Emit a plaintext control event to everyone in the room except the sender. */
async function broadcastToRoom(nsp, roomId, exceptDeviceId, event, payload) {
  for (const peer of await store.peersOf(roomId, exceptDeviceId)) {
    emitTo(nsp, roomId, peer.deviceId, event, payload);
  }
}

/**
 * Deliver now, or queue and wake.
 * `src` is stamped on so the recipient knows which key to open it with.
 * @returns {Promise<'live'|'queued'>}
 */
async function deliver({ nsp, roomId, dst, src, event, envelope }) {
  const target = await store.getDevice(roomId, dst);
  const payload = { ...envelope, src };

  if (target?.online) {
    emitTo(nsp, roomId, dst, event, payload);
    return 'live';
  }

  await store.enqueue(roomId, dst, { event, envelope: payload });

  if (WAKEABLE_EVENTS.has(event) && target?.fcmToken) {
    fcm
      .wake({
        fcmToken: target.fcmToken,
        event,
        roomId,
        urgent: URGENT_EVENTS.has(event),
      })
      .then((result) => {
        if (result.stale) return store.setFcmToken(roomId, dst, '');
        return undefined;
      })
      .catch((err) => logger.error({ err: err.message }, 'fcm wake threw'));
  }

  return 'queued';
}

function clampInt(value, min, max) {
  const n = Number.parseInt(value, 10);
  if (Number.isNaN(n)) return min < 0 ? min : 0;
  return Math.min(max, Math.max(min, n));
}

function text(value, max) {
  return typeof value === 'string' ? value.slice(0, max).replace(/[\u0000-\u001f\u007f-\u009f]/g, '') : '';
}

module.exports = { attachSignaling, isValidEnvelope };
