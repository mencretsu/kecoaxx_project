const admin = require("firebase-admin");
const fs = require("fs");

// baca service account
const serviceAccount = JSON.parse(fs.readFileSync("sa.json", "utf8"));

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: process.env.FIREBASE_DB_URL
});

const db = admin.database();

// ambil semua API key
db.ref("apikeys/api_keys").once("value")
  .then(snapshot => {
    const promises = [];
    snapshot.forEach(child => {
      // set disabled = false dan reset usedToday
      promises.push(child.ref.update({
        disabled: false,
        usedToday: 0,
        minuteCount: 0,
        lastUsed: 0,
        cooldownUntil: 0
      }));
    });

    return Promise.all(promises);
  })
  .then(() => {
    console.log("✅ Reset disabled = false dan usedToday = 0 selesai");
    process.exit(0);
  })
  .catch(err => {
    console.error("❌ Error reset disabled/usedToday:", err);
    process.exit(1);
  });
