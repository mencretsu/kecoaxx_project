package com.example.ngontol

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class OverlayTileService : TileService() {

    companion object {
        const val TAG = "OverlayTileService"
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onClick() {
        super.onClick()

        val isRunning = OverlayService.isServiceRunning(this)
        val intent = Intent(this, OverlayService::class.java)

        if (isRunning) {
            stopService(intent)
            Log.d(TAG, "Stopping OverlayService")
        } else {
            startForegroundService(intent)
            Log.d(TAG, "Starting OverlayService")
        }

        updateTile(!isRunning)

        Handler(Looper.getMainLooper()).postDelayed({
            updateTile(OverlayService.isServiceRunning(this))
        }, 500)
    }

    private fun updateTile(isActive: Boolean) {
        qsTile?.apply {
            state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Mode Mining"
            contentDescription = if (isActive) "Mining aktif" else "Mining nonaktif"
            updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile(OverlayService.isServiceRunning(this))
    }
}
