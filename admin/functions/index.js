"use strict";

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");

admin.initializeApp();

// >>> Set to the same Google account used in admin/app.js and the rules files. <<<
const OWNER_EMAIL = "REPLACE_ME@example.com";

const TOPIC = "all";
const STATIC_ACTIONS = ["create", "inspire", "stickers", "support", "wallpaper"];

function str(v, max) {
  return typeof v === "string" ? v.trim().slice(0, max) : "";
}

function validAction(a) {
  if (a === "") return true;
  if (STATIC_ACTIONS.includes(a)) return true;
  return /^url:https:\/\/\S+$/.test(a);
}

/**
 * Callable: sendPush({ title, body, action?, imageUrl? })
 * Owner only. Sends to FCM topic "all" with a notification block plus the data
 * payload keys the Android client expects: title, body, action, imageUrl.
 */
exports.sendPush = onCall({ region: "us-central1", maxInstances: 3 }, async (request) => {
  const auth = request.auth;
  if (!auth) {
    throw new HttpsError("unauthenticated", "Sign in first.");
  }
  if (auth.token.email !== OWNER_EMAIL || auth.token.email_verified !== true) {
    logger.warn("sendPush denied", { uid: auth.uid });
    throw new HttpsError("permission-denied", "Owner only.");
  }

  const data = request.data || {};
  const title = str(data.title, 100);
  const body = str(data.body, 500);
  const action = str(data.action, 500);
  const imageUrl = str(data.imageUrl, 1000);

  if (!title || !body) {
    throw new HttpsError("invalid-argument", "title and body are required.");
  }
  if (!validAction(action)) {
    throw new HttpsError("invalid-argument", "action must be create, inspire, stickers, support, wallpaper or url:https://...");
  }
  if (imageUrl && !/^https:\/\/\S+$/.test(imageUrl)) {
    throw new HttpsError("invalid-argument", "imageUrl must be an https URL.");
  }

  const message = {
    topic: TOPIC,
    notification: { title, body, ...(imageUrl ? { imageUrl } : {}) },
    // FCM data values must all be strings.
    data: { title, body, action, imageUrl },
    android: { priority: "high" },
  };

  try {
    const messageId = await admin.messaging().send(message);
    logger.info("push sent", { messageId });
    return { messageId };
  } catch (err) {
    logger.error("push failed", err);
    throw new HttpsError("internal", "FCM send failed: " + (err && err.message ? err.message : "unknown"));
  }
});
