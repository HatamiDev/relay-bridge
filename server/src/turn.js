'use strict';

/**
 * Time-limited TURN credentials (coturn `use-auth-secret` / REST API spec,
 * draft-uberti-behave-turn-rest-00).
 *
 *   username   = "<unix-expiry>:<opaque-user>"
 *   credential = base64( HMAC-SHA1( static-auth-secret, username ) )
 *
 * The long-term secret never leaves the server; handsets only ever receive a
 * derived credential that self-expires. coturn must be configured with:
 *
 *   use-auth-secret
 *   static-auth-secret=<TURN_STATIC_AUTH_SECRET>
 *   realm=<your-domain>
 */

const crypto = require('crypto');
const config = require('./config');

/**
 * @param {string} opaqueUser typically the roomId — never a phone number
 * @param {number} [ttlSeconds]
 * @returns {{username:string, credential:string, expiresAt:number}}
 */
function generateTurnCredential(opaqueUser, ttlSeconds = config.ice.turnTtlSeconds) {
  const expiry = Math.floor(Date.now() / 1000) + ttlSeconds;
  const username = `${expiry}:${opaqueUser}`;
  const credential = crypto
    .createHmac('sha1', config.ice.turnSecret)
    .update(username)
    .digest('base64');
  return { username, credential, expiresAt: expiry * 1000 };
}

/**
 * Build the full RTCIceServer list a handset should feed to PeerConnection.
 * STUN entries carry no credentials; TURN entries carry fresh HMAC ones.
 *
 * @param {string} roomId
 * @returns {{iceServers:Array<object>, expiresAt:number, ttlSeconds:number}}
 */
function buildIceServers(roomId) {
  /** @type {Array<object>} */
  const iceServers = [];

  if (config.ice.stunUrls.length) {
    iceServers.push({ urls: config.ice.stunUrls });
  }

  let expiresAt = Date.now() + config.ice.turnTtlSeconds * 1000;

  if (config.ice.turnUrls.length && config.ice.turnSecret) {
    const cred = generateTurnCredential(roomId);
    expiresAt = cred.expiresAt;
    iceServers.push({
      urls: config.ice.turnUrls,
      username: cred.username,
      credential: cred.credential,
      credentialType: 'password',
    });
  }

  return { iceServers, expiresAt, ttlSeconds: config.ice.turnTtlSeconds };
}

module.exports = { generateTurnCredential, buildIceServers };
