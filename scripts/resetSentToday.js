// scripts/resetSentToday.js
const admin = require("firebase-admin");

function fail(msg, err) {
  console.error("❌", msg);
  if (err) console.error(err);
  process.exit(1);
}

try {
  if (!process.env.FIREBASE_SA_B64) fail("env FIREBASE_SA_B64 missing");
  if (!process.env.FIREBASE_DB_URL) fail("env FIREBASE_DB_URL missing");
} catch (e) {
  fail("missing envs", e);
}

let serviceAccount;
try {
  serviceAccount = JSON.parse(
    Buffer.from(process.env.FIREBASE_SA_B64, "base64").toString("utf8")
  );
} catch (e) {
  fail("failed parsing FIREBASE_SA_B64 (not valid base64/json)", e);
}

try {
  admin.initializeApp({
    credential: admin.credential.cert(serviceAccount),
    databaseURL: process.env.FIREBASE_DB_URL,
  });
} catch (e) {
  fail("failed initializeApp", e);
}

const db = admin.database();

(async () => {
  try {
    console.log("🔔 Script start -", new Date().toISOString());
    const snap = await db.ref("devices").once("value");
    if (!snap.exists()) {
      console.log("⚠️ Tidak ada data di /devices");
      process.exit(0);
    }

    const tasks = [];
    let count = 0;
    snap.forEach((child) => {
      const deviceId = child.key;
      count++;
      console.log(" - will reset:", deviceId);
      tasks.push(db.ref(`devices/${deviceId}/sentToday`).set(0));
    });

    await Promise.all(tasks);
    console.log(`✅ Reset selesai. Total devices reset: ${count}`);
    process.exit(0);
  } catch (err) {
    fail("Error during reset", err);
  }
})();
