const admin = require("firebase-admin");
const fs = require("fs");

// decode service account dari env
const saB64 = process.env.FIREBASE_SA_B64;
fs.writeFileSync("sa.json", Buffer.from(saB64, "base64"));

const serviceAccount = require("./sa.json");

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: process.env.FIREBASE_DB_URL,
});

const db = admin.database();

db.ref("devices").once("value")
  .then(snapshot => {
    if (!snapshot.exists()) {
      console.log("⚠️ Tidak ada devices");
      process.exit(0);
    }

    snapshot.forEach(child => {
      child.ref.child("sentToday").set(0);
    });

    console.log("✅ Reset sentToday selesai!");
    process.exit(0);
  })
  .catch(err => {
    console.error("❌ Error reset sentToday:", err);
    process.exit(1);
  });
