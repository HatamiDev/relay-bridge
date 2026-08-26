'use strict';

/**
 * Redis-backed room store — multi-node twin of `./memoryStore.js`.
 *
 * Exposes byte-for-byte the same public method surface as the in-process
 * store (see that file's header for the room/device model this implements),
 * so `server.js` and `src/signaling.js` need no logic changes to run against
 * either backend — only `await`, since every method here is genuinely
 * asynchronous.
 *
 * ── Key design ──────────────────────────────────────────────────────────
 *
 *   relay:room:{roomId}              HASH   meta     → JSON {roomId, createdAt}
 *                                            gateway  → JSON Device
 *                                            code     → JSON {value, expiresAt} | "null"
 *   relay:room:{roomId}:receivers    HASH   deviceId → JSON Device
 *   relay:room:{roomId}:pending      HASH   deviceId → JSON pending-join record
 *   relay:room:{roomId}:queue:{did}  LIST   JSON envelope, oldest first, capped
 *                                            to OFFLINE_QUEUE_MAX via LTRIM
 *   relay:code:{code}                STRING roomId, native EX = pair-code TTL
 *   relay:device:{deviceId}          STRING roomId  (device → room index)
 *
 * The `relay:` prefix is `config.redis.keyPrefix` (default `relay:`).
 *
 * Pairing codes carry their TTL natively in Redis (`SET ... EX`) instead of
 * being swept by application code, so `expireCodes()` below is a documented
 * no-op — Redis deletes the key itself the instant it expires. The one
 * observable trade-off: because Redis makes an expired key indistinguishable
 * from one that never existed, `joinRoom` can no longer tell "expired" apart
 * from "unknown" the way the in-memory store could; both surface as the
 * `unknown_code` reason (404), where the memory store could return the more
 * specific `expired` (410). Callers already treat both as "can't join".
 *
 * The room hash's `code` field is best-effort bookkeeping only — it exists so
 * `issueCode`/`revokeCode` know which `relay:code:*` key to delete. Redis's
 * own TTL remains the sole source of truth for whether a code still works.
 *
 * Every write that touches more than one key (or more than one field that
 * must not be observed half-applied) goes through `MULTI`/`EXEC`.
 */

const { createClient } = require('redis');
const config = require('./config');
const logger = require('./logger');
const { generateRoomId, normalizePairCode, fingerprint } = require('./crypto');

const ROLE_GATEWAY = 'gateway';
const ROLE_RECEIVER = 'receiver';

class RedisStore {
  constructor() {
    this.prefix = config.redis.keyPrefix;

    this.client = createClient({
      url: config.redis.url,
      socket: {
        // Exponential backoff, capped at 5s, so a coturn-style flapping link
        // doesn't hammer the Redis host with immediate reconnect storms.
        reconnectStrategy: (retries) => Math.min(retries * 200, 5000),
        tls: config.redis.url.startsWith('rediss://'),
        rejectUnauthorized: config.redis.tlsRejectUnauthorized,
      },
    });

    this.client.on('connect', () => logger.info('redis connecting'));
    this.client.on('ready', () => logger.info('redis ready'));
    this.client.on('reconnecting', () => logger.warn('redis reconnecting'));
    this.client.on('error', (err) => logger.error({ err: err.message }, 'redis error'));
    this.client.on('end', () => logger.warn('redis connection closed'));

    // Fire-and-forget: node-redis v4 queues commands issued before the
    // connection is ready, so callers do not need to await this themselves.
    this.client.connect().catch((err) => logger.error({ err: err.message }, 'redis connect failed'));
  }

  // ── Key helpers ────────────────────────────────────────────────────────────

  _roomKey(roomId) {
    return `${this.prefix}room:${roomId}`;
  }

  _receiversKey(roomId) {
    return `${this.prefix}room:${roomId}:receivers`;
  }

  _pendingKey(roomId) {
    return `${this.prefix}room:${roomId}:pending`;
  }

  _queueKey(roomId, deviceId) {
    return `${this.prefix}room:${roomId}:queue:${deviceId}`;
  }

  _codeKey(code) {
    return `${this.prefix}code:${code}`;
  }

  _deviceKey(deviceId) {
    return `${this.prefix}device:${deviceId}`;
  }

  /** Write a device record back to whichever hash it belongs in. */
  async _writeDevice(roomId, d) {
    if (d.role === ROLE_GATEWAY) {
      await this.client.hSet(this._roomKey(roomId), 'gateway', JSON.stringify(d));
    } else {
      await this.client.hSet(this._receiversKey(roomId), d.deviceId, JSON.stringify(d));
    }
  }

  // ── Room creation ──────────────────────────────────────────────────────────

  /**
   * A gateway claims a new room and the first pairing code.
   * @param {{pairCode:string, deviceId:string, model?:string, label?:string,
   *          fcmToken?:string, pubKey:string}} args
   */
  async createRoom({ pairCode, deviceId, model = '', label = '', fcmToken = '', pubKey }) {
    // A device may only own one room; re-creating replaces the old one so a
    // factory-reset gateway does not leak an orphan room forever.
    const existingRoomId = await this.client.get(this._deviceKey(deviceId));
    if (existingRoomId) await this.destroyRoom(existingRoomId);

    const roomId = generateRoomId();
    const ttl = config.auth.pairCodeTtlSeconds;
    const expiresAt = Date.now() + ttl * 1000;
    const code = normalizePairCode(pairCode);
    const gateway = device({ deviceId, role: ROLE_GATEWAY, model, label, fcmToken, pubKey });
    const meta = { roomId, createdAt: Date.now() };

    await this.client
      .multi()
      .hSet(this._roomKey(roomId), {
        meta: JSON.stringify(meta),
        gateway: JSON.stringify(gateway),
        code: JSON.stringify({ value: code, expiresAt }),
      })
      .set(this._codeKey(code), roomId, { EX: ttl })
      .set(this._deviceKey(deviceId), roomId)
      .exec();

    logger.info({ room: fingerprint(roomId) }, 'room created');
    return { roomId, expiresAt };
  }

  /** Mint a fresh code for an existing room so another receiver can join. */
  async issueCode(roomId, pairCode, gatewayPubKey) {
    const [gatewayRaw, prevCodeRaw] = await this.client.hmGet(this._roomKey(roomId), [
      'gateway',
      'code',
    ]);
    if (!gatewayRaw) return null;

    const gateway = JSON.parse(gatewayRaw);
    const prevCode = prevCodeRaw ? JSON.parse(prevCodeRaw) : null;

    const code = normalizePairCode(pairCode);
    const ttl = config.auth.pairCodeTtlSeconds;
    const expiresAt = Date.now() + ttl * 1000;
    // The gateway rotates its ephemeral key per code, so refresh it too.
    if (gatewayPubKey) gateway.pubKey = gatewayPubKey;

    const multi = this.client.multi();
    if (prevCode?.value) multi.del(this._codeKey(prevCode.value));
    multi
      .hSet(this._roomKey(roomId), {
        gateway: JSON.stringify(gateway),
        code: JSON.stringify({ value: code, expiresAt }),
      })
      .set(this._codeKey(code), roomId, { EX: ttl });
    await multi.exec();

    return { roomId, expiresAt };
  }

  async revokeCode(roomId) {
    const codeRaw = await this.client.hGet(this._roomKey(roomId), 'code');
    const prevCode = codeRaw ? JSON.parse(codeRaw) : null;
    if (!prevCode?.value) return false;

    await this.client
      .multi()
      .del(this._codeKey(prevCode.value))
      .hSet(this._roomKey(roomId), 'code', JSON.stringify(null))
      .exec();
    return true;
  }

  // ── Joining ────────────────────────────────────────────────────────────────

  /**
   * A receiver redeems a code.
   *
   * Unlike a one-shot invite, the code stays valid until it expires or the
   * gateway revokes it — that is what makes "one code, several receivers"
   * work without the user re-generating it for every phone.
   *
   * @returns {Promise<{ok:true, room:object} | {ok:false, reason:string}>}
   */
  async joinRoom({ pairCode, deviceId, model = '', label = '', fcmToken = '', pubKey }) {
    const code = normalizePairCode(pairCode);
    const roomId = await this.client.get(this._codeKey(code));
    // A miss here means "unknown" — see the key-design note above for why
    // Redis's native TTL means we can no longer distinguish that from
    // "expired" the way the in-memory store could.
    if (!roomId) return { ok: false, reason: 'unknown_code' };

    const gatewayRaw = await this.client.hGet(this._roomKey(roomId), 'gateway');
    if (!gatewayRaw) return { ok: false, reason: 'room_missing' };
    const gateway = JSON.parse(gatewayRaw);
    if (gateway.deviceId === deviceId) return { ok: false, reason: 'cannot_join_own_room' };

    const total = await this.client.hLen(this._receiversKey(roomId));
    if (total >= config.relay.maxReceiversPerRoom) return { ok: false, reason: 'room_full' };

    // Re-joining with the same deviceId replaces the old record and its key,
    // which is what happens when a receiver is re-paired after a wipe —
    // HSET on an existing field simply overwrites it.
    const record = device({ deviceId, role: ROLE_RECEIVER, model, label, fcmToken, pubKey });
    const pendingEntry = { deviceId, pubKey, model, label, joinedAt: Date.now(), pairCode: code };

    await this.client
      .multi()
      .hSet(this._receiversKey(roomId), deviceId, JSON.stringify(record))
      .hSet(this._pendingKey(roomId), deviceId, JSON.stringify(pendingEntry))
      .set(this._deviceKey(deviceId), roomId)
      .exec();

    const [receivers, pending] = await Promise.all([
      this.receiversOf(roomId),
      this.pendingJoins(roomId),
    ]);

    logger.info({ room: fingerprint(roomId), receivers: receivers.length }, 'receiver joined');
    return { ok: true, room: { roomId, gateway, receivers, pending } };
  }

  /** Gateway acknowledges a receiver after comparing the SAS. */
  async confirmReceiver(roomId, deviceId) {
    const [pendingRaw, receiverRaw] = await Promise.all([
      this.client.hGet(this._pendingKey(roomId), deviceId),
      this.client.hGet(this._receiversKey(roomId), deviceId),
    ]);
    if (!pendingRaw && !receiverRaw) return false;

    const multi = this.client.multi();
    if (pendingRaw) multi.hDel(this._pendingKey(roomId), deviceId);
    if (receiverRaw) {
      const receiver = JSON.parse(receiverRaw);
      receiver.confirmed = true;
      multi.hSet(this._receiversKey(roomId), deviceId, JSON.stringify(receiver));
    }
    await multi.exec();
    return true;
  }

  async pendingJoins(roomId) {
    const all = await this.client.hGetAll(this._pendingKey(roomId));
    return Object.values(all).map((v) => JSON.parse(v));
  }

  // ── Lookup ─────────────────────────────────────────────────────────────────

  async getRoom(roomId) {
    const [metaRaw, gatewayRaw] = await this.client.hmGet(this._roomKey(roomId), [
      'meta',
      'gateway',
    ]);
    if (!metaRaw) return null;
    return { ...JSON.parse(metaRaw), gateway: gatewayRaw ? JSON.parse(gatewayRaw) : null };
  }

  /** Any device in the room by id, gateway or receiver. */
  async getDevice(roomId, deviceId) {
    const gatewayRaw = await this.client.hGet(this._roomKey(roomId), 'gateway');
    if (gatewayRaw) {
      const gateway = JSON.parse(gatewayRaw);
      if (gateway.deviceId === deviceId) return gateway;
    }
    const receiverRaw = await this.client.hGet(this._receiversKey(roomId), deviceId);
    return receiverRaw ? JSON.parse(receiverRaw) : null;
  }

  /** Everyone except [exceptDeviceId], as light peer descriptors. */
  async peersOf(roomId, exceptDeviceId) {
    const [gatewayRaw, receiversRaw] = await Promise.all([
      this.client.hGet(this._roomKey(roomId), 'gateway'),
      this.client.hGetAll(this._receiversKey(roomId)),
    ]);

    const devices = [];
    if (gatewayRaw) devices.push(JSON.parse(gatewayRaw));
    for (const raw of Object.values(receiversRaw)) devices.push(JSON.parse(raw));

    return devices
      .filter((d) => d && d.deviceId !== exceptDeviceId)
      .map((d) => ({
        deviceId: d.deviceId,
        role: d.role,
        model: d.model,
        online: d.online,
        lastSeen: d.lastSeen,
      }));
  }

  async gatewayOf(roomId) {
    const raw = await this.client.hGet(this._roomKey(roomId), 'gateway');
    return raw ? JSON.parse(raw) : null;
  }

  async receiversOf(roomId) {
    const all = await this.client.hGetAll(this._receiversKey(roomId));
    return Object.values(all).map((v) => JSON.parse(v));
  }

  // ── Presence ───────────────────────────────────────────────────────────────

  async markOnline(roomId, deviceId, socketId) {
    const d = await this.getDevice(roomId, deviceId);
    if (!d) return null;
    d.online = true;
    d.socketId = socketId;
    d.lastSeen = Date.now();
    await this._writeDevice(roomId, d);
    return d;
  }

  async markOffline(roomId, deviceId, socketId) {
    const d = await this.getDevice(roomId, deviceId);
    if (!d) return null;
    // Ignore a stale disconnect racing a fresh reconnect.
    if (socketId && d.socketId && d.socketId !== socketId) return d;
    d.online = false;
    d.socketId = null;
    d.lastSeen = Date.now();
    await this._writeDevice(roomId, d);
    return d;
  }

  async setFcmToken(roomId, deviceId, fcmToken) {
    const d = await this.getDevice(roomId, deviceId);
    if (!d) return false;
    d.fcmToken = fcmToken || '';
    await this._writeDevice(roomId, d);
    return true;
  }

  // ── Removal ────────────────────────────────────────────────────────────────

  /** Remove one receiver. Removing the gateway destroys the room. */
  async removeDevice(roomId, deviceId) {
    const gatewayRaw = await this.client.hGet(this._roomKey(roomId), 'gateway');
    if (!gatewayRaw) return false;
    const gateway = JSON.parse(gatewayRaw);

    if (gateway.deviceId === deviceId) {
      await this.destroyRoom(roomId);
      return true;
    }

    const existed = await this.client.hExists(this._receiversKey(roomId), deviceId);
    if (!existed) return false;

    await this.client
      .multi()
      .hDel(this._receiversKey(roomId), deviceId)
      .hDel(this._pendingKey(roomId), deviceId)
      .del(this._queueKey(roomId, deviceId))
      .del(this._deviceKey(deviceId))
      .exec();
    return true;
  }

  async destroyRoom(roomId) {
    const [gatewayRaw, codeRaw, receiversRaw] = await Promise.all([
      this.client.hGet(this._roomKey(roomId), 'gateway'),
      this.client.hGet(this._roomKey(roomId), 'code'),
      this.client.hGetAll(this._receiversKey(roomId)),
    ]);
    if (!gatewayRaw) return false;

    const gateway = JSON.parse(gatewayRaw);
    const code = codeRaw ? JSON.parse(codeRaw) : null;
    const receiverIds = Object.keys(receiversRaw);

    const multi = this.client
      .multi()
      .del(this._roomKey(roomId))
      .del(this._receiversKey(roomId))
      .del(this._pendingKey(roomId))
      .del(this._deviceKey(gateway.deviceId))
      .del(this._queueKey(roomId, gateway.deviceId));

    for (const id of receiverIds) {
      multi.del(this._deviceKey(id));
      multi.del(this._queueKey(roomId, id));
    }
    if (code?.value) multi.del(this._codeKey(code.value));

    await multi.exec();
    logger.warn({ room: fingerprint(roomId) }, 'room destroyed');
    return true;
  }

  /**
   * Drop codes whose TTL elapsed. A no-op here — see the key-design note at
   * the top of this file. Kept so `server.js`'s periodic sweep needs no
   * branching between backends.
   */
  async expireCodes(_now = Date.now()) {
    // Redis evicts `relay:code:*` keys itself once their EX elapses.
  }

  // ── Offline queue ──────────────────────────────────────────────────────────

  /**
   * Buffer an envelope for a device that is not connected.
   * Bounded FIFO per room so a long outage cannot exhaust memory.
   */
  async enqueue(roomId, dst, item) {
    const key = this._queueKey(roomId, dst);
    const entry = JSON.stringify({ dst, ...item, queuedAt: Date.now() });
    const results = await this.client
      .multi()
      .rPush(key, entry)
      .lTrim(key, -config.relay.offlineQueueMax, -1)
      .lLen(key)
      .exec();
    return results[results.length - 1];
  }

  async drain(roomId, dst) {
    const key = this._queueKey(roomId, dst);
    // MULTI so a concurrent enqueue cannot land between the read and the
    // delete and get silently dropped.
    const [items] = await this.client.multi().lRange(key, 0, -1).del(key).exec();
    if (!items || !items.length) return [];
    return items.map((raw) => JSON.parse(raw));
  }

  async queueDepth(roomId, dst) {
    return this.client.lLen(this._queueKey(roomId, dst));
  }

  /**
   * Approximate fleet-wide counts for `/health`. Uses SCAN (never KEYS) so a
   * large keyspace cannot block the event loop on a production instance.
   */
  async stats() {
    let rooms = 0;
    let receivers = 0;
    let onlineDevices = 0;
    let activeCodes = 0;

    const roomKeyPrefix = `${this.prefix}room:`;
    for await (const key of this.client.scanIterator({ MATCH: `${roomKeyPrefix}*`, COUNT: 100 })) {
      // Skip the :receivers / :pending / :queue:* suffixed keys — only count
      // the base per-room hash.
      if (key.includes(':receivers') || key.includes(':pending') || key.includes(':queue:')) {
        continue;
      }
      rooms += 1;

      const roomId = key.slice(roomKeyPrefix.length);
      const [gatewayRaw, receiversRaw] = await Promise.all([
        this.client.hGet(key, 'gateway'),
        this.client.hGetAll(this._receiversKey(roomId)),
      ]);
      if (gatewayRaw && JSON.parse(gatewayRaw).online) onlineDevices += 1;

      const list = Object.values(receiversRaw).map((v) => JSON.parse(v));
      receivers += list.length;
      onlineDevices += list.filter((d) => d.online).length;
    }

    for await (const _key of this.client.scanIterator({
      MATCH: `${this.prefix}code:*`,
      COUNT: 100,
    })) {
      activeCodes += 1;
    }

    return { rooms, receivers, activeCodes, onlineDevices };
  }

  // ── Persistence ────────────────────────────────────────────────────────────

  /**
   * No-op: unlike the in-memory store, every mutation above already lands in
   * Redis (durable via AOF — see deploy/redis/redis.conf) the moment it
   * happens, so there is no local write buffer to flush. Kept so
   * `server.js`'s shutdown path needs no branching between backends.
   */
  async flush() {
    return true;
  }
}

function device({ deviceId, role, model, label, fcmToken, pubKey }) {
  return {
    deviceId,
    role,
    model,
    label,
    fcmToken,
    pubKey,
    confirmed: role === ROLE_GATEWAY,
    online: false,
    lastSeen: 0,
    socketId: null,
    joinedAt: Date.now(),
  };
}

module.exports = new RedisStore();
module.exports.RedisStore = RedisStore;
module.exports.ROLE_GATEWAY = ROLE_GATEWAY;
module.exports.ROLE_RECEIVER = ROLE_RECEIVER;
