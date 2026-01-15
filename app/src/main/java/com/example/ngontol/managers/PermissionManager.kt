package com.example.ngontol.managers

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionManager {
    const val REQUEST_LOCATION = 1001
    const val REQUEST_MOCK_LOCATION = 1003

    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestLocationPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            REQUEST_LOCATION
        )
    }

    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun openLocationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        context.startActivity(intent)
    }

    fun isMockLocationEnabled(context: Context): Boolean {
        return try {
            val devOptions = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            )
            devOptions == 1
        } catch (_: Exception) {
            true // Assume enabled
        }
    }

    fun openDeveloperSettings(activity: Activity) {
        try {
            activity.startActivityForResult(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                REQUEST_MOCK_LOCATION
            )
        } catch (_: Exception) {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
