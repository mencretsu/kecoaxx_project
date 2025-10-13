package com.example.ngontol

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlin.random.Random

class FakeGpsService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    private var currentLat = 0.0
    private var currentLng = 0.0
    private var cityName = ""

    companion object {
        private const val CHANNEL_ID = "FakeGpsServiceChannel"
        private const val NOTIFICATION_ID = 1001
        private const val UPDATE_INTERVAL = 5000L // Update setiap 5 detik
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()

        // Load koordinat dari SharedPreferences
        loadFakeGpsCoordinates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Mulai foreground service dengan notifikasi
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Mulai update fake GPS secara periodik
        startPeriodicUpdate()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // Hentikan update periodik
        stopPeriodicUpdate()

        // Matikan mock mode
        disableMockMode()

        Log.d("FakeGpsService", "Service destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Fake GPS Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menjaga fake GPS tetap aktif"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fake GPS Aktif")
            .setContentText("Lokasi: $cityName")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun loadFakeGpsCoordinates() {
        val prefs = getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
        currentLat = prefs.getFloat("fake_gps_lat", 0f).toDouble()
        currentLng = prefs.getFloat("fake_gps_lng", 0f).toDouble()
        cityName = prefs.getString("fake_gps_city", "Unknown") ?: "Unknown"

        Log.d("FakeGpsService", "Loaded coordinates: $cityName ($currentLat, $currentLng)")
    }

    @SuppressLint("MissingPermission")
    private fun startPeriodicUpdate() {
        updateRunnable = object : Runnable {
            override fun run() {
                if (currentLat != 0.0 && currentLng != 0.0) {
                    setMockLocation(currentLat, currentLng)
                }
                handler.postDelayed(this, UPDATE_INTERVAL)
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun stopPeriodicUpdate() {
        updateRunnable?.let {
            handler.removeCallbacks(it)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setMockLocation(lat: Double, lng: Double) {
        try {
            val mockLocation = Location("fused").apply {
                latitude = lat
                longitude = lng
                accuracy = 15f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

                // Tambahkan properti tambahan untuk realistis
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    bearingAccuracyDegrees = 10f
                    speedAccuracyMetersPerSecond = 1f
                    verticalAccuracyMeters = 3f
                }

                // Variasi kecil untuk simulasi gerakan alami
                bearing = Random.nextInt(0, 361).toFloat()
                speed = Random.nextFloat() * 2f // 0-2 m/s (berjalan kaki)
            }

            fusedClient.setMockMode(true)
                .addOnSuccessListener {
                    fusedClient.setMockLocation(mockLocation)
                        .addOnSuccessListener {
                            Log.d("FakeGpsService", "lokasi updated: $lat, $lng")
                        }
                        .addOnFailureListener { e ->
                            Log.e("FakeGpsService", "Gagal to set lokasi palsu: ${e.message}")
                            // Coba tanpa mock mode
                            fusedClient.setMockLocation(mockLocation)
                        }
                }
                .addOnFailureListener { e ->
                    Log.e("FakeGpsService", "Failed to enable mock mode: ${e.message}")
                    // Coba langsung set mock location
                    fusedClient.setMockLocation(mockLocation)
                }
        } catch (e: Exception) {
            Log.e("FakeGpsService", "Error setting mock location: ${e.message}", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun disableMockMode() {
        try {
            fusedClient.setMockMode(false)
                .addOnSuccessListener {
                    Log.d("FakeGpsService", "Mock mode disabled")
                }
                .addOnFailureListener { e ->
                    Log.e("FakeGpsService", "Failed to disable mock mode: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e("FakeGpsService", "Error disabling mock mode: ${e.message}")
        }
    }
}