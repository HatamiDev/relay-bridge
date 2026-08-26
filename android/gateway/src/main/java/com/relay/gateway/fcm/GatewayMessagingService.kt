package com.relay.gateway.fcm

/*
 * Intentionally empty.
 *
 * Android binds exactly one FirebaseMessagingService per application, and the
 * merged APK contains both halves of the bridge — so the single FCM entry point
 * now lives in the app module at `com.relay.app.RelayMessagingService`, which
 * dispatches to the gateway or the receiver based on the stored role.
 *
 * This file is kept as a marker so the package does not silently disappear and
 * confuse anyone following an older revision of the docs.
 */
