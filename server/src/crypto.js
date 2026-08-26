'use strict';

/**
 * Server-side cryptographic helpers.
 *
 * IMPORTANT: nothing here can decrypt user traffic. The server has no access to
 * the E2EE root key — that is transported optically (QR) between the two
 * handsets and never leaves them. These primitives exist purely for pairing
 * codes, room identifiers and constant-time comparison.
 */

const crypto = require('crypto');

/** Crockford Base32 — no I, L, O, U, so codes cannot be misread aloud. */
const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/**
 * Cryptographically random pairing code, grouped for readability.
 *
 * Rejection sampling keeps the distribution uniform over the 32-symbol
 * alphabet. Eight symbols is ~40 bits — enough that guessing it inside the
 * 5-minute TTL is hopeless against the endpoint rate limiter, and short enough
 * that a person can read it aloud across a room without a mistake.
 *
 * @param {number} length total symbol count (default 8 → ~40 bits)
 * @returns {string} e.g. "K7M4-QW2X"
 */
function generatePairCode(length = 8) {
  const symbols = [];
  while (symbols.length < length) {
    for (const byte of crypto.randomBytes(length * 2)) {
      if (byte >= 248) continue; // 248 = 256 - (256 % 32); discard bias
      symbols.push(CROCKFORD[byte % 32]);
      if (symbols.length === length) break;
    }
  }
  return symbols.join('').replace(/(.{4})(?=.)/g, '$1-');
}

/** Normalise user-typed codes: strip separators, upcase, map look-alikes. */
function normalizePairCode(raw) {
  return String(raw || '')
    .toUpperCase()
    .replace(/[\s-]/g, '')
    .replace(/O/g, '0')
    .replace(/[IL]/g, '1')
    .replace(/U/g, 'V');
}

/** Opaque, unguessable room identifier. */
function generateRoomId() {
  return crypto.randomBytes(16).toString('base64url');
}

/** Opaque device identifier fallback when a handset does not supply one. */
function generateDeviceId() {
  return crypto.randomBytes(12).toString('base64url');
}

/**
 * Constant-time string comparison that does not leak length through timing
 * beyond the unavoidable early return on mismatched byte length.
 * @param {string} a @param {string} b
 */
function safeEqual(a, b) {
  const bufA = Buffer.from(String(a), 'utf8');
  const bufB = Buffer.from(String(b), 'utf8');
  if (bufA.length !== bufB.length) {
    // Still burn a comparison so the branch is not trivially timeable.
    crypto.timingSafeEqual(bufA, bufA);
    return false;
  }
  return crypto.timingSafeEqual(bufA, bufB);
}

/** SHA-256 → base64url. Used to key rate limiters without storing raw values. */
function fingerprint(value) {
  return crypto.createHash('sha256').update(String(value)).digest('base64url').slice(0, 22);
}

module.exports = {
  generatePairCode,
  normalizePairCode,
  generateRoomId,
  generateDeviceId,
  safeEqual,
  fingerprint,
};
