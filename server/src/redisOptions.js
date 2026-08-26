'use strict';

/**
 * One place that turns the Redis env vars into node-redis client options.
 *
 * Two consumers need identical connection settings: `./redisStore.js` for the
 * room state, and the Socket.IO Redis adapter in `./signaling.js` for
 * cross-process delivery. If those two ever pointed at different databases the
 * failure would be maddening — devices would authenticate against state in one
 * place while their messages were published into another — so the options are
 * built once, here, and both import them.
 */

const config = require('./config');

/** True when Redis is configured at all, by either transport. */
function redisEnabled() {
  return Boolean(config.redis.socketPath || config.redis.url);
}

/**
 * @returns {object} options for node-redis v4 `createClient`
 * @throws {Error} when called with no Redis configured — a caller that reaches
 *   here without checking [redisEnabled] has a logic bug worth surfacing loudly
 *   rather than silently connecting to a default localhost that is not there.
 */
function redisClientOptions() {
  if (!redisEnabled()) {
    throw new Error('[redis] no REDIS_SOCKET_PATH or REDIS_URL configured');
  }

  // Unix socket wins when both are present: a filesystem path is unambiguous
  // about which instance is meant, a host:port on shared hosting is not.
  if (config.redis.socketPath) {
    const options = {
      socket: {
        path: config.redis.socketPath,
        reconnectStrategy: (retries) => Math.min(retries * 200, 5000),
      },
      database: config.redis.database,
    };
    // node-redis sends AUTH only when a password is present; an empty string
    // would be sent as a real (wrong) password against an unauthenticated
    // instance and fail the connection.
    if (config.redis.password) options.password = config.redis.password;
    return options;
  }

  const options = {
    url: config.redis.url,
    socket: {
      reconnectStrategy: (retries) => Math.min(retries * 200, 5000),
      tls: config.redis.url.startsWith('rediss://'),
      rejectUnauthorized: config.redis.tlsRejectUnauthorized,
    },
  };
  // A `/db` suffix in the URL already selects the database; only override when
  // REDIS_DB was set explicitly, so the URL stays authoritative by default.
  if (config.redis.database) options.database = config.redis.database;
  if (config.redis.password) options.password = config.redis.password;
  return options;
}

/** Human-readable endpoint for logs. Never includes the password. */
function redisEndpointLabel() {
  const db = config.redis.database ? ` db=${config.redis.database}` : '';
  if (config.redis.socketPath) return `unix:${config.redis.socketPath}${db}`;
  return `${config.redis.url.replace(/\/\/[^@]*@/, '//')}${db}`;
}

module.exports = { redisEnabled, redisClientOptions, redisEndpointLabel };
