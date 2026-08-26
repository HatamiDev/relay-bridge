'use strict';

/**
 * Room state: one gateway, many receivers.
 *
 * A room is created by a gateway and holds:
 *   • the gateway device record (including its ephemeral pairing public key)
 *   • zero or more receiver device records, each with its own public key
 *   • an outstanding pairing code (single code, reusable until revoked/expired)
 *   • a bounded offline queue keyed by target deviceId
 *
 * The server relays public keys but never sees a derived root key, so it cannot
 * read any envelope it carries. It *could* substitute a public key during
 * pairing — which is what the handsets' 6-digit SAS comparison exists to catch.
 *
 * In-process map + periodic JSON snapshot. This is the single-node backend
 * selected by `./store.js` when `REDIS_URL` is unset — see `./redisStore.js`
 * for the multi-node backend, which exposes byte-for-byte the same method
 * surface (all methods here are `async` purely to keep that contract uniform;
 * every operation is actually synchronous under the hood).
 */

const fs = require('fs');
const path = require('path');
const config = require('./config');
const logger = require('./logger');
const { generateRoomId, normalizePairCode, fingerprint } = require('./crypto');

const ROLE_GATEWAY = 'gateway';
const ROLE_RECEIVER = 'receiver';

class MemoryStore {
  constructor() {
    /** @type {Map<string, Room>} roomId → room */
    this.rooms = new Map();
    /** @type {Map<string, {roomId:string, expiresAt:number}>} pairCode → room */
    this.codes = new Map();
    /** @type {Map<string, string>} deviceId → roomId */
    this.deviceIndex = new Map();

    this._dirty = false;
    this._restore();
    this._snapshotTimer = setInterval(() => this._snapshot(), config.relay.snapshotIntervalMs);
    this._snapshotTimer.unref?.();
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
    const existingRoomId = this.deviceIndex.get(deviceId);
    if (existingRoomId) await this.destroyRoom(existingRoomId);

    const roomId = generateRoomId();
    const expiresAt = Date.now() + config.auth.pairCodeTtlSeconds * 1000;
    const code = normalizePairCode(pairCode);

    /** @type {Room} */
    const room = {
      roomId,
      createdAt: Date.now(),
      gateway: device({ deviceId, role: ROLE_GATEWAY, model, label, fcmToken, pubKey }),
      receivers: [],
      pending: [],
      code: { value: code, expiresAt },
      queue: [],
    };

    this.rooms.set(roomId, room);
    this.codes.set(code, { roomId, expiresAt });
    this.deviceIndex.set(deviceId, roomId);
    this._dirty = true;

    logger.info({ room: fingerprint(roomId) }, 'room created');
    return { roomId, expiresAt };
  }

  /** Mint a fresh code for an existing room so another receiver can join. */
  async issueCode(roomId, pairCode, gatewayPubKey) {
    const room = this.rooms.get(roomId);
    if (!room) return null;

    if (room.code?.value) this.codes.delete(room.code.value);

    const code = normalizePairCode(pairCode);
    const expiresAt = Date.now() + config.auth.pairCodeTtlSeconds * 1000;

    room.code = { value: code, expiresAt };
    // The gateway rotates its ephemeral key per code, so refresh it too.
    if (gatewayPubKey) room.gateway.pubKey = gatewayPubKey;

    this.codes.set(code, { roomId, expiresAt });
    this._dirty = true;
    return { roomId, expiresAt };
  }

  async revokeCode(roomId) {
    const room = this.rooms.get(roomId);
    if (!room?.code?.value) return false;
    this.codes.delete(room.code.value);
    room.code = null;
    this._dirty = true;
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
   * @returns {Promise<{ok:true, room:Room} | {ok:false, reason:string}>}
   */
  async joinRoom({ pairCode, deviceId, model = '', label = '', fcmToken = '', pubKey }) {
    const code = normalizePairCode(pairCode);
    const entry = this.codes.get(code);

    if (!entry) return { ok: false, reason: 'unknown_code' };
    if (Date.now() > entry.expiresAt) {
      this.codes.delete(code);
      const stale = this.rooms.get(entry.roomId);
      if (stale) stale.code = null;
      return { ok: false, reason: 'expired' };
    }

    const room = this.rooms.get(entry.roomId);
    if (!room) return { ok: false, reason: 'room_missing' };
    if (room.gateway.deviceId === deviceId) return { ok: false, reason: 'cannot_join_own_room' };

    const total = room.receivers.length;
    if (total >= config.relay.maxReceiversPerRoom) {
      return { ok: false, reason: 'room_full' };
    }

    const record = device({ deviceId, role: ROLE_RECEIVER, model, label, fcmToken, pubKey });

    // Re-joining with the same deviceId replaces the old record and its key,
    // which is what happens when a receiver is re-paired after a wipe.
    room.receivers = room.receivers.filter((r) => r.deviceId !== deviceId);
    room.receivers.push(record);

    room.pending = room.pending.filter((p) => p.deviceId !== deviceId);
    room.pending.push({
      deviceId,
      pubKey,
      model,
      label,
      joinedAt: Date.now(),
      pairCode: code,
    });

    this.deviceIndex.set(deviceId, room.roomId);
    this._dirty = true;

    logger.info(
      { room: fingerprint(room.roomId), receivers: room.receivers.length },
      'receiver joined',
    );
    return { ok: true, room };
  }

  /** Gateway acknowledges a receiver after comparing the SAS. */
  async confirmReceiver(roomId, deviceId) {
    const room = this.rooms.get(roomId);
    if (!room) return false;
    const before = room.pending.length;
    room.pending = room.pending.filter((p) => p.deviceId !== deviceId);
    const receiver = room.receivers.find((r) => r.deviceId === deviceId);
    if (receiver) receiver.confirmed = true;
    this._dirty = true;
    return room.pending.length !== before || Boolean(receiver);
  }

  async pendingJoins(roomId) {
    return this.rooms.get(roomId)?.pending ?? [];
  }

  // ── Lookup ─────────────────────────────────────────────────────────────────

  async getRoom(roomId) {
    return this.rooms.get(roomId) || null;
  }

  /** Any device in the room by id, gateway or receiver. */
  async getDevice(roomId, deviceId) {
    const room = this.rooms.get(roomId);
    if (!room) return null;
    if (room.gateway.deviceId === deviceId) return room.gateway;
    return room.receivers.find((r) => r.deviceId === deviceId) || null;
  }

  /** Everyone except [exceptDeviceId], as light peer descriptors. */
  async peersOf(roomId, exceptDeviceId) {
    const room = this.rooms.get(roomId);
    if (!room) return [];
    return [room.gateway, ...room.receivers]
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
    return this.rooms.get(roomId)?.gateway ?? null;
  }

  async receiversOf(roomId) {
    return this.rooms.get(roomId)?.receivers ?? [];
  }

  // ── Presence ───────────────────────────────────────────────────────────────

  async markOnline(roomId, deviceId, socketId) {
    const d = await this.getDevice(roomId, deviceId);
    if (!d) return null;
    d.online = true;
    d.socketId = socketId;
    d.lastSeen = Date.now();
    this._dirty = true;
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
    this._dirty = true;
    return d;
  }

  async setFcmToken(roomId, deviceId, fcmToken) {
    const d = await this.getDevice(roomId, deviceId);
    if (!d) return false;
    d.fcmToken = fcmToken || '';
    this._dirty = true;
    return true;
  }

  // ── Removal ────────────────────────────────────────────────────────────────

  /** Remove one receiver. Removing the gateway destroys the room. */
  async removeDevice(roomId, deviceId) {
    const room = this.rooms.get(roomId);
    if (!room) return false;

    if (room.gateway.deviceId === deviceId) {
      await this.destroyRoom(roomId);
      return true;
    }

    const before = room.receivers.length;
    room.receivers = room.receivers.filter((r) => r.deviceId !== deviceId);
    room.pending = room.pending.filter((p) => p.deviceId !== deviceId);
    room.queue = room.queue.filter((q) => q.dst !== deviceId);
    this.deviceIndex.delete(deviceId);
    this._dirty = true;
    return room.receivers.length !== before;
  }

  async destroyRoom(roomId) {
    const room = this.rooms.get(roomId);
    if (!room) return false;
    if (room.code?.value) this.codes.delete(room.code.value);
    this.deviceIndex.delete(room.gateway.deviceId);
    for (const r of room.receivers) this.deviceIndex.delete(r.deviceId);
    this.rooms.delete(roomId);
    this._dirty = true;
    logger.warn({ room: fingerprint(roomId) }, 'room destroyed');
    return true;
  }

  /** Drop codes whose TTL elapsed. Rooms themselves survive. */
  async expireCodes(now = Date.now()) {
    for (const [code, entry] of this.codes) {
      if (now > entry.expiresAt) {
        this.codes.delete(code);
        const room = this.rooms.get(entry.roomId);
        if (room?.code?.value === code) room.code = null;
        this._dirty = true;
      }
    }
  }

  // ── Offline queue ──────────────────────────────────────────────────────────

  /**
   * Buffer an envelope for a device that is not connected.
   * Bounded FIFO per room so a long outage cannot exhaust memory.
   */
  async enqueue(roomId, dst, item) {
    const room = this.rooms.get(roomId);
    if (!room) return 0;
    room.queue.push({ dst, ...item, queuedAt: Date.now() });
    while (room.queue.length > config.relay.offlineQueueMax) room.queue.shift();
    this._dirty = true;
    return room.queue.length;
  }

  async drain(roomId, dst) {
    const room = this.rooms.get(roomId);
    if (!room || room.queue.length === 0) return [];
    const mine = room.queue.filter((q) => q.dst === dst);
    room.queue = room.queue.filter((q) => q.dst !== dst);
    this._dirty = true;
    return mine;
  }

  async queueDepth(roomId, dst) {
    const room = this.rooms.get(roomId);
    if (!room) return 0;
    return room.queue.reduce((n, q) => n + (q.dst === dst ? 1 : 0), 0);
  }

  async stats() {
    let online = 0;
    let receivers = 0;
    for (const room of this.rooms.values()) {
      if (room.gateway?.online) online += 1;
      receivers += room.receivers.length;
      online += room.receivers.filter((r) => r.online).length;
    }
    return {
      rooms: this.rooms.size,
      receivers,
      activeCodes: this.codes.size,
      onlineDevices: online,
    };
  }

  // ── Persistence ────────────────────────────────────────────────────────────

  _snapshot() {
    if (!this._dirty) return;
    try {
      fs.mkdirSync(path.dirname(config.relay.snapshotPath), { recursive: true });
      const payload = {
        version: 2,
        savedAt: Date.now(),
        rooms: [...this.rooms.values()].map((room) => ({
          ...room,
          // Sockets never survive a restart; queues and keys do.
          gateway: { ...room.gateway, online: false, socketId: null },
          receivers: room.receivers.map((r) => ({ ...r, online: false, socketId: null })),
        })),
      };
      const tmp = `${config.relay.snapshotPath}.tmp`;
      fs.writeFileSync(tmp, JSON.stringify(payload), 'utf8');
      fs.renameSync(tmp, config.relay.snapshotPath);
      this._dirty = false;
    } catch (err) {
      logger.error({ err: err.message }, 'snapshot failed');
    }
  }

  _restore() {
    try {
      if (!fs.existsSync(config.relay.snapshotPath)) return;
      const raw = JSON.parse(fs.readFileSync(config.relay.snapshotPath, 'utf8'));
      if (raw.version !== 2) {
        logger.warn({ version: raw.version }, 'snapshot version mismatch; starting empty');
        return;
      }
      for (const room of raw.rooms || []) {
        room.receivers = room.receivers || [];
        room.pending = room.pending || [];
        room.queue = room.queue || [];
        this.rooms.set(room.roomId, room);
        this.deviceIndex.set(room.gateway.deviceId, room.roomId);
        for (const r of room.receivers) this.deviceIndex.set(r.deviceId, room.roomId);
        if (room.code?.value && Date.now() < room.code.expiresAt) {
          this.codes.set(room.code.value, {
            roomId: room.roomId,
            expiresAt: room.code.expiresAt,
          });
        } else {
          room.code = null;
        }
      }
      logger.info({ rooms: this.rooms.size }, 'state restored');
    } catch (err) {
      logger.warn({ err: err.message }, 'snapshot restore failed; starting empty');
    }
  }

  async flush() {
    this._dirty = true;
    this._snapshot();
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

/**
 * @typedef {{deviceId:string, role:string, model:string, label:string,
 *            fcmToken:string, pubKey:string, confirmed:boolean, online:boolean,
 *            lastSeen:number, socketId:string|null, joinedAt:number}} Device
 * @typedef {{roomId:string, createdAt:number, gateway:Device, receivers:Device[],
 *            pending:object[], code:{value:string,expiresAt:number}|null,
 *            queue:object[]}} Room
 */

module.exports = new MemoryStore();
module.exports.MemoryStore = MemoryStore;
module.exports.ROLE_GATEWAY = ROLE_GATEWAY;
module.exports.ROLE_RECEIVER = ROLE_RECEIVER;
