const admin = require("firebase-admin");
const fs = require("fs");

// baca service account
const serviceAccount = JSON.parse(fs.readFileSync("sa.json", "utf8"));

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: process.env.FIREBASE_DB_URL
});

const db = admin.database();
const today = new Date().toISOString().split("T")[0]; // "YYYY-MM-DD"

db.ref("devices").once("value")
  .then(snapshot => {
    let totalSentAllDevices = 0;

    snapshot.forEach(child => {
      totalSentAllDevices += child.val().sentToday || 0;
    });

    const historyRef = db.ref(`sentHistory/${today}/totalSent`);
    return historyRef.set(totalSentAllDevices)
      .then(() => snapshot);
  })
  // .then(snapshot => {
  //   const promises = [];
  //   snapshot.forEach(child => {
  //     promises.push(child.ref.child("sentToday").set(0));
  //   });
  //   return Promise.all(promises);
  // })
  .then(() => {
    console.log("✅ totalSent dicatat & sentToday direset ke 0");
    process.exit(0);
  })
  .catch(err => {
    console.error("❌ Error:", err);
    process.exit(1);
  });

