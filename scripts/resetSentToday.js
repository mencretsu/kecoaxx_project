/**
 * resetSentToday.js
 * Reset sentToday untuk semua device (WIB 00:00)
 */

const admin = require("firebase-admin");
const fs = require("fs");

// ===== INIT FIREBASE =====
const serviceAccount = JSON.parse(fs.readFileSync("sa.json", "utf8"));

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: process.env.FIREBASE_DB_URL
});

const db = admin.database();

// ===== WIB DATE (LOG ONLY) =====
function getWIBDate() {
  const now = new Date();
  const wib = new Date(now.getTime() + 7 * 60 * 60 * 1000);
  return wib.toISOString().split("T")[0];
}

// ===== MAIN =====
async function main() {
  try {
    const today = getWIBDate();
    console.log(`🔁 Reset sentToday | ${today} WIB`);

    const usersSnap = await db.ref("users").once("value");

    const updates = {};
    let totalReset = 0;

    usersSnap.forEach(userChild => {
      const username = userChild.key;
      const userData = userChild.val();

      if (!userData.devices) return;

      Object.keys(userData.devices).forEach(deviceId => {
        updates[`users/${username}/devices/${deviceId}/sentToday`] = 0;
        totalReset++;
      });
    });

    if (totalReset === 0) {
      console.log("⚠️ No devices found, nothing to reset");
      process.exit(0);
    }

    await db.ref().update(updates);

    console.log(`✅ sentToday reset complete (${totalReset} devices)`);
    process.exit(0);
  } catch (err) {
    console.error("❌ Reset failed:", err);
    process.exit(1);
  }
}

main();
