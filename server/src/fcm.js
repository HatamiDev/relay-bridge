'use strict';

/**
 * Firebase Cloud Messaging waker.
 *
 * When an event must reach a peer whose socket is not connected (Doze, app
 * swiped away, screen off for hours) we send a **data-only, high-priority**
 * message. Data-only matters: a notification-payload message would be handled
 * by the system tray and would not necessarily start our process, whereas a
 * data message is delivered to `FirebaseMessagingService.onMessageReceived`,
 * which is granted a short foreground-service start window.
 *
 * The push carries NO user content — only a wake hint and an event name. The
 * real (encrypted) payload is fetched from the offline queue once the device
 * reconnects its socket, so Google never sees anything meaningful.
 */

const fs = require('fs');
const config = require('./config');
const logger = require('./logger');

let messaging = null;
let enabled = false;

function init() {
  if (!config.fcm.serviceAccountPath) {
    logger.warn('FCM disabled: FCM_SERVICE_ACCOUNT_PATH not set');
    return;
  }
  if (!fs.existsSync(config.fcm.serviceAccountPath)) {
    logger.warn({ path: config.fcm.serviceAccountPath }, 'FCM disabled: service account file missing');
    return;
  }
  try {
    // Required lazily so the dependency is optional at runtime.
    const admin = require('firebase-admin');
    const serviceAccount = JSON.parse(fs.readFileSync(config.fcm.serviceAccountPath, 'utf8'));
    const app = admin.apps.length
      ? admin.app()
      : admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
    messaging = admin.messaging(app);
    enabled = true;
    logger.info({ project: serviceAccount.project_id }, 'FCM initialised');
  } catch (err) {
    logger.error({ err: err.message }, 'FCM initialisation failed');
  }
}

/**
 * Wake a device.
 *
 * @param {object}  args
 * @param {string}  args.fcmToken           registration token of the target
 * @param {string}  args.event              e.g. "sms:inbound" | "call:incoming"
 * @param {string}  args.roomId             opaque room identifier
 * @param {boolean} [args.urgent=false]     true for calls — bypasses Doze harder
 * @param {string}  [args.callId]           correlates the ring with the socket event
 * @returns {Promise<{sent:boolean, reason?:string, messageId?:string}>}
 */
async function wake({ fcmToken, event, roomId, urgent = false, callId = '' }) {
  if (!enabled || !messaging) return { sent: false, reason: 'fcm_disabled' };
  if (!fcmToken) return { sent: false, reason: 'no_token' };

  /** @type {import('firebase-admin').messaging.Message} */
  const message = {
    token: fcmToken,
    // Data-only. No `notification` block anywhere.
    data: {
      type: 'wake',
      event,
      room: roomId,
      callId,
      urgent: String(urgent),
      ts: String(Date.now()),
    },
    android: {
      priority: 'high',
      // Calls must never be collapsed or delayed; SMS wakes may coalesce.
      collapseKey: urgent ? undefined : `wake:${event}`,
      ttl: urgent ? 45_000 : 6 * 60 * 60 * 1000,
      directBootOk: true,
    },
    apns: undefined, // Android-only system
  };

  try {
    const messageId = await messaging.send(message);
    logger.debug({ event, urgent }, 'fcm wake sent');
    return { sent: true, messageId };
  } catch (err) {
    const code = err?.errorInfo?.code || err?.code || 'unknown';
    // A dead token should be cleared by the caller so we stop retrying it.
    const stale =
      code === 'messaging/registration-token-not-registered' ||
      code === 'messaging/invalid-registration-token' ||
      code === 'messaging/invalid-argument';
    logger.warn({ code, stale }, 'fcm wake failed');
    return { sent: false, reason: code, stale };
  }
}

module.exports = { init, wake, isEnabled: () => enabled };
