'use strict';

/**
 * Structured logging.
 *
 * Anything that could carry user content is redacted. The server is a
 * zero-knowledge relay: it must never persist or print ciphertext, IVs,
 * pairing codes, JWTs or TURN credentials.
 */

const pino = require('pino');
const config = require('./config');

const REDACT_PATHS = [
  'req.headers.authorization',
  'req.headers.cookie',
  'req.headers["x-bootstrap-secret"]',
  'body.pairCode',
  'body.bootstrapSecret',
  'body.fcmToken',
  'envelope.ct',
  'envelope.iv',
  'token',
  'jwt',
  'credential',
  '*.credential',
  '*.ct',
  '*.iv',
];

/**
 * Where the log actually goes.
 *
 * Default is stdout, which is right for systemd (journald picks it up) and for
 * local development. It is useless under LiteSpeed/Passenger, which captures
 * only stderr and throws stdout away — so on that host every `info` and `warn`
 * vanished, including the "socket auth rejected" line that explains exactly
 * why a handshake failed. Debugging without it is guesswork.
 *
 * Set LOG_FILE to a path to get a real file instead. Redaction still applies:
 * the file never receives ciphertext, IVs, pairing codes, JWTs or credentials.
 */
function destination() {
  if (config.logFile) {
    return pino.destination({ dest: config.logFile, append: true, sync: false });
  }
  return pino.destination(1);
}

const logger = pino(
  {
    level: config.logLevel,
    redact: { paths: REDACT_PATHS, censor: '[redacted]' },
    base: { svc: 'relay-signaling' },
    timestamp: pino.stdTimeFunctions.isoTime,
  },
  destination(),
);

module.exports = logger;
