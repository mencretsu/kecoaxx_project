package com.example.ngontol.managers

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.firebase.database.FirebaseDatabase
import java.io.File

object UpdateManager {
    private const val DB_URL = "https://kecoaxx-db898-default-rtdb.asia-southeast1.firebasedatabase.app"

    fun checkForUpdate(
        activity: AppCompatActivity,
        onNoUpdate: () -> Unit
    ) {
        val db = FirebaseDatabase.getInstance(DB_URL)

        db.getReference("update").get().addOnSuccessListener { snap ->
            val latestCode = snap.child("latestVersionCode").getValue(Int::class.java) ?: 0
            val latestName = snap.child("latestVersionName").getValue(String::class.java) ?: "?"
            val apkUrl = snap.child("apkUrl").getValue(String::class.java) ?: ""
            val changelog = snap.child("changelog").getValue(String::class.java) ?: ""

            val currentCode = getCurrentVersionCode(activity)

            if (latestCode > currentCode) {
                showUpdateDialog(activity, latestName, changelog, apkUrl)
            } else {
                onNoUpdate()
            }
        }.addOnFailureListener {
            onNoUpdate()
        }
    }

    private fun getCurrentVersionCode(context: Context): Long {
        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }
    }

    private fun showUpdateDialog(
        activity: AppCompatActivity,
        latestName: String,
        changelog: String,
        apkUrl: String
    ) {
        AlertDialog.Builder(activity)
            .setTitle("Update Tersedia (v$latestName)")
            .setMessage("Changelog:\n$changelog")
            .setPositiveButton("Perbarui") { _, _ ->
                downloadAndInstall(activity, apkUrl)
            }
            .setNegativeButton("Nanti", null)
            .show()
    }

    @SuppressLint("Range", "UnspecifiedRegisterReceiverFlag")
    private fun downloadAndInstall(activity: AppCompatActivity, apkUrl: String) {
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Mengunduh kecoaxx...")
            .setDescription("Update kecoaxx")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(
                activity,
                Environment.DIRECTORY_DOWNLOADS,
                "update_app.apk"
            )


        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    activity.unregisterReceiver(this)

                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = dm.query(query)
                    if (cursor.moveToFirst()) {
                        val localUri = cursor.getString(
                            cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        )
                        val file = File(Uri.parse(localUri).path ?: return)

                        val apkUri = FileProvider.getUriForFile(
                            activity,
                            "${activity.packageName}.provider",
                            file
                        )

                        val installIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(apkUri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        activity.startActivity(installIntent)
                    }
                    cursor.close()
                }
            }
        }

        activity.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }
}
