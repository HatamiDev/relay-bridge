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

const logger = pino({
  level: config.logLevel,
  redact: { paths: REDACT_PATHS, censor: '[redacted]' },
  base: { svc: 'relay-signaling' },
  timestamp: pino.stdTimeFunctions.isoTime,
  transport: config.isProd
    ? undefined
    : { target: 'pino/file', options: { destination: 1 } }, // stdout, human order preserved
});

module.exports = logger;
