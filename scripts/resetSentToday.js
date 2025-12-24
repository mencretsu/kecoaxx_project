const admin = require("firebase-admin");
const fs = require("fs");

const serviceAccount = JSON.parse(fs.readFileSync("sa.json", "utf8"));
admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: "https://kecoaxx-db898-default-rtdb.asia-southeast1.firebasedatabase.app"
});

const db = admin.database();

async function saveSnapshot() {
  try {
    // Timezone WIB (UTC+7)
    const now = new Date();
    const wibDate = new Date(now.getTime() + (7 * 60 * 60 * 1000));
    const today = wibDate.toISOString().split('T')[0];
    
    console.log(`📅 Saving snapshot for: ${today} WIB`);
    
    const usersSnapshot = await db.ref("users").once("value");
    const snapshot = {};
    let totalDevices = 0;
    let totalSentAll = 0;
    
    usersSnapshot.forEach(userChild => {
      const username = userChild.key;
      const userData = userChild.val();
      
      if (userData.devices) {
        Object.entries(userData.devices).forEach(([deviceId, device]) => {
          const totalSent = device.totalSent || 0;
          
          snapshot[deviceId] = {
            username: username,
            botName: device.botName || "Unknown",
            deviceName: device.deviceName || "Unknown",
            totalSent: totalSent,
            battery: device.battery !== undefined ? device.battery : device.batteryLevel,
            status: device.status || "unknown"
          };
          
          totalDevices++;
          totalSentAll += totalSent;
        });
      }
    });
    
    await db.ref(`dailySnapshots/${today}`).set({
      timestamp: Date.now(),
      totalDevices: totalDevices,
      totalSent: totalSentAll,
      devices: snapshot
    });
    
    console.log(`✅ Snapshot saved: ${totalDevices} devices, ${totalSentAll} total sent`);
    
    // Deteksi device tidak aktif (compare dengan kemarin)
    await detectInactive(today);
    
    process.exit(0);
  } catch (err) {
    console.error("❌ Error:", err);
    process.exit(1);
  }
}

async function detectInactive(today) {
  try {
    const yesterday = new Date(Date.now() - 86400000 + (7 * 60 * 60 * 1000))
      .toISOString().split('T')[0];
    
    console.log(`\n🔍 Comparing ${yesterday} vs ${today}...`);
    
    const todaySnapshot = await db.ref(`dailySnapshots/${today}`).once("value");
    const yesterdaySnapshot = await db.ref(`dailySnapshots/${yesterday}`).once("value");
    
    if (!yesterdaySnapshot.exists()) {
      console.log("⚠️ No data from yesterday, skipping comparison");
      return;
    }
    
    const todayData = todaySnapshot.val().devices;
    const yesterdayData = yesterdaySnapshot.val().devices;
    
    const inactiveDevices = [];
    
    Object.entries(todayData).forEach(([deviceId, device]) => {
      const yesterdayDevice = yesterdayData[deviceId];
      
      if (yesterdayDevice) {
        const growth = device.totalSent - yesterdayDevice.totalSent;
        
        if (growth === 0 && device.totalSent > 0) {
          inactiveDevices.push({
            deviceId: deviceId,
            username: device.username,
            botName: device.botName,
            deviceName: device.deviceName,
            totalSent: device.totalSent
          });
        }
      }
    });
    
    console.log(`\n📊 Results:`);
    console.log(`✅ Active devices: ${Object.keys(todayData).length - inactiveDevices.length}`);
    console.log(`❌ Inactive devices: ${inactiveDevices.length}`);
    
    if (inactiveDevices.length > 0) {
      console.log(`\n🚨 Inactive devices:`);
      inactiveDevices.forEach((d, i) => {
        console.log(`${i + 1}. ${d.botName} (@${d.username}) - ${d.deviceName}`);
      });
      
      // Save list inactive devices
      await db.ref(`inactiveDevices/${today}`).set({
        count: inactiveDevices.length,
        devices: inactiveDevices,
        checkedAt: Date.now()
      });
      
      console.log(`\n💾 Inactive list saved to: inactiveDevices/${today}`);
    }
    
  } catch (err) {
    console.error("❌ Error detecting inactive:", err);
  }
}

saveSnapshot();
