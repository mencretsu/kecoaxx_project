const admin = require("firebase-admin");

const serviceAccount = JSON.parse(
  Buffer.from(process.env.FIREBASE_SA_B64, "base64").toString("utf8")
);

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: process.env.FIREBASE_DB_URL,
});

const db = admin.database();

(async () => {
  try {
    const snap = await db.ref("devices").once("value");
    if (!snap.exists()) {
      console.log("⚠️ Tidak ada data di /devices");
      process.exit(0);
    }

    const tasks = [];
    snap.forEach((child) => {
      const deviceId = child.key;
      console.log(`🔄 Reset sentToday untuk device: ${deviceId}`);
      tasks.push(
        db.ref(`devices/${deviceId}/sentToday`).set(0)
      );
    });

    await Promise.all(tasks);

    console.log("✅ Semua sentToday berhasil direset ke 0");
    process.exit(0);
  } catch (err) {
    console.error("❌ Error reset:", err);
    process.exit(1);
  }
})();
