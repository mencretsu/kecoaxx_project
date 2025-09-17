const admin = require("firebase-admin");
const fs = require("fs");

// baca service account dari file yang dibuat di workflow
const serviceAccount = JSON.parse(fs.readFileSync("sa.json", "utf8"));

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: process.env.FIREBASE_DB_URL
});

const db = admin.database();

db.ref("devices").once("value")
  .then(snapshot => {
    snapshot.forEach(child => {
      child.ref.child("sentToday").set(0);
    });
    console.log("✅ Reset sentToday selesai");
    process.exit(0);
  })
  .catch(err => {
    console.error("❌ Error reset sentToday:", err);
    process.exit(1);
  });
