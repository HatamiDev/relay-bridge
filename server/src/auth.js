'use strict';

/**
 * Device authentication.
 *
 * Devices authenticate with a stateless JWT minted during pairing. The token
 * binds a deviceId to a role and a roomId; the socket layer refuses any event
 * whose implied route does not match the token's room, and refuses any device
 * the room no longer lists (so removing a receiver takes effect immediately
 * even though its JWT has not expired).
 */

const jwt = require('jsonwebtoken');
const config = require('./config');

const ROLES = Object.freeze({ GATEWAY: 'gateway', RECEIVER: 'receiver' });
const VALID_ROLES = new Set(Object.values(ROLES));

/**
 * @param {{deviceId:string, role:'gateway'|'receiver', roomId:string}} claims
 * @returns {{token:string, expiresAt:number}}
 */
function issueDeviceToken({ deviceId, role, roomId }) {
  if (!VALID_ROLES.has(role)) throw new Error(`Unknown role: ${role}`);
  const expiresAt = Date.now() + config.auth.jwtTtlSeconds * 1000;
  const token = jwt.sign(
    { sub: deviceId, role, room: roomId },
    config.auth.jwtSecret,
    {
      algorithm: 'HS256',
      expiresIn: config.auth.jwtTtlSeconds,
      issuer: 'relay-signaling',
      audience: 'relay-device',
    },
  );
  return { token, expiresAt };
}

/**
 * @param {string} token
 * @returns {{deviceId:string, role:string, roomId:string, exp:number}}
 * @throws {Error} when absent, malformed, expired or wrong-audience
 */
function verifyDeviceToken(token) {
  if (!token || typeof token !== 'string') throw new Error('missing token');
  const decoded = jwt.verify(token, config.auth.jwtSecret, {
    algorithms: ['HS256'],
    issuer: 'relay-signaling',
    audience: 'relay-device',
  });
  if (!VALID_ROLES.has(decoded.role)) throw new Error('invalid role claim');
  if (!decoded.room) throw new Error('missing room claim');
  return {
    deviceId: decoded.sub,
    role: decoded.role,
    roomId: decoded.room,
    exp: decoded.exp,
  };
}

/** Guards the room-creation endpoint so strangers cannot use your server. */
function requireBootstrapSecret(req, res, next) {
  const { safeEqual } = require('./crypto');
  const provided = req.get('x-bootstrap-secret') || req.body?.bootstrapSecret || '';
  if (!safeEqual(provided, config.auth.bootstrapSecret)) {
    return res.status(401).json({ error: 'unauthorized' });
  }
  return next();
}

/**
 * Attaches `req.device` from a Bearer token, and re-checks that the device is
 * still enrolled — a JWT alone must not outlive removal from the room.
 *
 * Async because the store lookup may be a Redis round-trip; the try/catch
 * covers both the synchronous JWT verify and the awaited store call, so a
 * rejected promise here can never reach Express as an unhandled rejection.
 */
async function requireDeviceToken(req, res, next) {
  const store = require('./store');
  const header = req.get('authorization') || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : '';
  try {
    const claims = verifyDeviceToken(token);
    const enrolled = await store.getDevice(claims.roomId, claims.deviceId);
    if (!enrolled) {
      return res.status(401).json({ error: 'device_not_enrolled' });
    }
    req.device = claims;
    return next();
  } catch {
    return res.status(401).json({ error: 'unauthorized' });
  }
}

/** Chain after [requireDeviceToken] for gateway-only endpoints. */
function requireGateway(req, res, next) {
  if (req.device?.role !== ROLES.GATEWAY) {
    return res.status(403).json({ error: 'gateway_only' });
  }
  return next();
}

module.exports = {
  ROLES,
  issueDeviceToken,
  verifyDeviceToken,
  requireBootstrapSecret,
  requireDeviceToken,
  requireGateway,
};
