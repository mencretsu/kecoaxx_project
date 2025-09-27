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
      // set disabled = false
      promises.push(child.ref.child("disabled").set(false));
    });

    return Promise.all(promises);
  })
  .then(() => {
    console.log("✅ Reset disabled = false selesai");
    process.exit(0);
  })
  .catch(err => {
    console.error("❌ Error reset disabled:", err);
    process.exit(1);
  });
