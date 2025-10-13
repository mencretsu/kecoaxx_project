package com.example.ngontol.managers

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.SystemClock
import com.example.ngontol.FakeGpsService
import com.google.android.gms.location.FusedLocationProviderClient
import kotlinx.coroutines.tasks.await
import kotlin.math.*
import kotlin.random.Random

class FakeGpsManager(
    private val context: Context,
    private val fusedClient: FusedLocationProviderClient
) {

    data class City(val name: String, val lat: Double, val lng: Double)

    val cities = listOf(
        City("Fake GPS [OFF]", 0.0, 0.0),
        City("Ambon", -3.6950, 128.1814),
        City("Balikpapan", -1.2675, 116.8289),
        City("Bandar Lampung", -5.4294, 105.2610),
        City("Bandung", -6.9175, 107.6191),
        City("Banjarmasin", -3.3167, 114.5900),
        City("Batam", 1.0456, 104.0305),
        City("Bekasi", -6.2416, 106.9924),
        City("Bogor", -6.5950, 106.8166),
        City("Cilegon", -6.0023, 106.0110),
        City("Cirebon", -6.7063, 108.5570),
        City("Denpasar", -8.6705, 115.2126),
        City("Depok", -6.4025, 106.7942),
        City("Jakarta", -6.2088, 106.8456),
        City("Jayapura", -2.5916, 140.6689),
        City("Kupang", -10.1772, 123.6070),
        City("Makassar", -5.1477, 119.4327),
        City("Malang", -7.9666, 112.6326),
        City("Manado", 1.4748, 124.8421),
        City("Mataram", -8.5833, 116.1167),
        City("Medan", 3.5952, 98.6722),
        City("Padang", -0.9471, 100.4172),
        City("Palembang", -2.9909, 104.7566),
        City("Palu", -0.9003, 119.8779),
        City("Pangkal Pinang", -2.1291, 106.1133),
        City("Pekanbaru", 0.5071, 101.4478),
        City("Pontianak", -0.0227, 109.3304),
        City("Samarinda", -0.5022, 117.1536),
        City("Semarang", -6.9667, 110.4167),
        City("Solo", -7.5755, 110.8243),
        City("Sorong", -0.8761, 131.2558),
        City("Surabaya", -7.2575, 112.7521),
        City("Tangerang", -6.1783, 106.6319),
        City("Tegal", -6.8694, 109.1256),
        City("Yogyakarta", -7.7956, 110.3695)
    )


    @SuppressLint("MissingPermission")
    suspend fun enableFakeGps(
        cityIndex: Int,
        onSuccess: (String, Double, Double) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            if (cityIndex <= 0) {
                disableFakeGps(onSuccess, onError)
                return
            }

            val city = cities[cityIndex]
            val (lat, lng) = randomLocationAround(city.lat, city.lng)

            val mockLoc = Location("fused").apply {
                latitude = lat
                longitude = lng
                accuracy = 15f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    bearingAccuracyDegrees = 10f
                    speedAccuracyMetersPerSecond = 1f
                    verticalAccuracyMeters = 3f
                }
            }

            try {
                fusedClient.setMockMode(true).await()
                fusedClient.setMockLocation(mockLoc).await()
            } catch (e: Exception) {
                // Fallback without mock mode
                fusedClient.setMockLocation(mockLoc).await()
            }

            saveSelection(cityIndex, city.name, lat, lng)
            startFakeGpsService()
            onSuccess(city.name, lat, lng)

        } catch (e: Exception) {
            onError("Gagal mengaktifkan fake GPS: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun disableFakeGps(
        onSuccess: (String, Double, Double) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            stopFakeGpsService()

            try {
                fusedClient.setMockMode(false).await()
            } catch (e: Exception) {
                // Ignore error
            }

            saveSelection(0, "OFF", 0.0, 0.0)
            onSuccess("OFF", 0.0, 0.0)

        } catch (e: Exception) {
            onError("Gagal menonaktifkan fake GPS: ${e.message}")
        }
    }

    fun getSavedSelection(): Int {
        return context.getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
            .getInt("fake_gps_position", 0)
    }

    fun getSavedCityName(): String {
        return context.getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
            .getString("fake_gps_city", "OFF") ?: "OFF"
    }

    private fun saveSelection(position: Int, cityName: String, lat: Double, lng: Double) {
        context.getSharedPreferences("bot_prefs", Context.MODE_PRIVATE).edit().apply {
            putInt("fake_gps_position", position)
            putString("fake_gps_city", cityName)
            putFloat("fake_gps_lat", lat.toFloat())
            putFloat("fake_gps_lng", lng.toFloat())
            putBoolean("fake_gps_enabled", position > 0)
            apply()
        }
    }

    private fun startFakeGpsService() {
        val intent = Intent(context, FakeGpsService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopFakeGpsService() {
        context.stopService(Intent(context, FakeGpsService::class.java))
    }

    private fun randomLocationAround(
        centerLat: Double,
        centerLng: Double,
        minRadius: Double = 10000.0,
        maxRadius: Double = 40000.0
    ): Pair<Double, Double> {
        val u = Random.nextDouble()
        val r = minRadius + (maxRadius - minRadius) * sqrt(u)
        val theta = Random.nextDouble() * 2.0 * Math.PI

        val R = 6378137.0
        val deltaLat = (r * cos(theta)) / R
        val deltaLng = (r * sin(theta)) / (R * cos(Math.toRadians(centerLat)))

        val newLat = centerLat + Math.toDegrees(deltaLat)
        val newLng = centerLng + Math.toDegrees(deltaLng)

        return Pair(newLat, newLng)
    }
}
