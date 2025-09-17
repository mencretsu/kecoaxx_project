package com.example.ngontol

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import com.example.ngontol.databinding.ActivityMainBinding
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.content.edit
import androidx.core.net.toUri
import com.example.ngontol.auth.AuthManager
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.text.InputType
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.example.ngontol.model.User
import com.google.firebase.database.FirebaseDatabase
import java.io.File
import kotlin.jvm.java

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var botRunning = false
    private var selectedModel: BotPersona = BotPersona.GENZ_CENTIL
    private var isServiceEnabled: Boolean = false

    @SuppressLint("SetTextI18n", "UseKtx")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 2001 && resultCode == RESULT_OK && data != null) {
            val pm = packageManager
            var packageName: String? = data.component?.packageName
                ?: data.dataString
                ?: (data.extras?.get("android.intent.extra.shortcut.INTENT") as? Intent)?.component?.packageName
                ?: data.extras?.getString("android.intent.extra.PACKAGE_NAME")

            if (packageName.isNullOrBlank()) {
                toast("❌ Gagal ambil package dari picker")
                Log.e("APP_PICKER", "data=$data, extras=${data.extras}")
                return
            }

            // whitelist aplikasi yg didukung
            val supportedPkgs = listOf(
                "com.voicemaker.android",
                "com.hwsj.club"
            )

            if (packageName !in supportedPkgs) {
                toast("❌ Layanan belum tersedia untuk $packageName")
                return
            }

            // ambil nama app asli
            val appName: String = try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                packageName
            }

            // update EditText & SharedPreferences
            b.etTargetName.setText(appName)
            getSharedPreferences("bot_prefs", MODE_PRIVATE).edit().apply {
                putString("last_app_name", appName)
                putString("last_app_package", packageName)
                apply()
            }

            toast("✅ Aplikasi dipilih: $appName ($packageName)")
        }

    }

    @RequiresApi(Build.VERSION_CODES.P)
    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentUser = AuthManager.getSavedUser(this)
        if (currentUser == null) {
            showLoginDialog()
        } else {
            // langsung ke home
            toast("Auto login sebagai ${currentUser.phone}")
        }
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnConfig.setOnClickListener {
            if (b.panelForm.visibility == View.VISIBLE) {
                b.panelForm.visibility = View.GONE
            } else {
                b.panelForm.visibility = View.VISIBLE
                b.btnConfig.visibility = View.GONE
            }
        }
        // 1. Setup Spinner Persona
        val personaLabels = BotPersona.entries.map { it.label }
        val adapter = ArrayAdapter(this, R.layout.spinner_item, personaLabels)
        adapter.setDropDownViewResource(R.layout.spinner_item)
        b.spinnerPersona.adapter = adapter

        // 2. Ambil model terakhir yang dipilih dari SharedPreferences
        val sharedPrefs = getSharedPreferences("bot_prefs", MODE_PRIVATE)
        val savedModelName = sharedPrefs.getString("selected_model", BotPersona.GENZ_CENTIL.name)
        val savedModelIndex = BotPersona.entries.toTypedArray().indexOfFirst { it.name == savedModelName }
        if (savedModelIndex >= 0) {
            b.spinnerPersona.setSelection(savedModelIndex)
            selectedModel = BotPersona.entries.toTypedArray()[savedModelIndex]
        }
        b.etTargetName.apply {
            isFocusable = false
            isClickable = true
            setOnClickListener {
                val intent = Intent(Intent.ACTION_PICK_ACTIVITY)
                val mainIntent = Intent(Intent.ACTION_MAIN, null)
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
                intent.putExtra(Intent.EXTRA_INTENT, mainIntent)
                startActivityForResult(intent, 2001)
            }
        }

        // 3. Listener Spinner
        b.spinnerPersona.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                selectedModel = BotPersona.entries.toTypedArray()[position]
                sharedPrefs.edit { putString("selected_model", selectedModel.name) }
                tampilkanInfoProfilTersimpan()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // 4. Tombol Save Persona
        b.btnSavePersona.setOnClickListener {
            val prefs = getSharedPreferences("bot_prefs", MODE_PRIVATE)

            // ambil data lama dari sharedprefs
            val lastAppName = prefs.getString("last_app_name", null)
            val lastAppPackage = prefs.getString("last_app_package", null)

            // fallback ke nilai lama kalau kosong
            val appName = lastAppName ?: ""
            val appPackage = lastAppPackage ?: ""

            // fallback botName (misalnya pakai yg lama)
            val oldPersona = PersonaManager.getPersona(this)

// pastiin convert kalau data lama masih string
            val oldBlacklist: MutableList<String> = when (val bl = oldPersona?.blacklist) {
                is List<*> -> bl.filterIsInstance<String>().toMutableList()
                is String -> if (bl.isNotBlank()) bl.split(",").map { it.trim() }.toMutableList() else mutableListOf()
                else -> mutableListOf()
            }

            val newItem = b.etBlacklist.text.toString().trim()
            if (newItem.isNotBlank()) {
                oldBlacklist.add(newItem)
            }

            val persona = Persona(
                botName = if (b.etBotName.text.isNotBlank()) b.etBotName.text.toString()
                else oldPersona?.botName ?: "",
                gender = if (b.etGender.text.isNotBlank()) b.etGender.text.toString()
                else oldPersona?.gender ?: "",
                address = if (b.etAddress.text.isNotBlank()) b.etAddress.text.toString()
                else oldPersona?.address ?: "",
                hobby = if (b.etHobby.text.isNotBlank()) b.etHobby.text.toString()
                else oldPersona?.hobby ?: "",
                blacklist = oldBlacklist, // sekarang pasti List<String>
                appName = if (!appName.isNullOrBlank()) appName else oldPersona?.appName,
                appPackage = if (!appPackage.isNullOrBlank()) appPackage else oldPersona?.appPackage
            )



            // validasi minimal: harus ada nama & aplikasi (dari baru ATAU lama)
            if (persona.botName.isBlank()) {
                toast("Isi nama dulu!")
                return@setOnClickListener
            }
            if (persona.appPackage.isNullOrBlank() || persona.appName.isNullOrBlank()) {
                toast("Pilih aplikasi dulu! ")
                return@setOnClickListener
            }

            PersonaManager.savePersona(this, persona)
            toast("BERHASIL TERSIMPAN ✅")
            b.panelForm.visibility = View.GONE
            b.btnConfig.visibility = View.VISIBLE
            tampilkanInfoProfilTersimpan()
        }

        // 5. Tombol Start Bot
        b.btnStart.setOnClickListener {
            val serviceActive = isMyBotServiceAlive()
            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (!serviceActive) {
                val ready = checkPrerequisites()
                if (ready) {
                    botRunning = true
                    b.btnStart.text = "Stop Bot"
                    b.btnStart.setBackgroundColor("#FF1744".toColorInt())
                    if (!b.tvStatus.text.contains("Bot AKTIF")) {
                        b.tvStatus.append("\n\uD83D\uDFE2 BOT AKTIF [$currentTime]")
                        b.tvStatus.setTextColor("#00E676".toColorInt())
                    }
                }
            } else {
                val stopIntent = Intent(this, MyBotService::class.java).apply {
                    action = MyBotService.ACTION_STOP
                }
                startService(stopIntent)
                botRunning = false
                b.btnStart.text = "Start Bot"
                val berhentiText = "\n\uD83D\uDD34 BOT BERHENTI [$currentTime]"
                val spannable = SpannableString(berhentiText)
                spannable.setSpan(
                    ForegroundColorSpan("#FF1744".toColorInt()),
                    0,
                    berhentiText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                b.tvStatus.append(spannable)
                b.btnStart.setBackgroundColor("#00E676".toColorInt())
            }
        }

        // 6. Tombol Cek Update
        b.btnCheckUpdate.setOnClickListener {
            checkUpdate()  // ⬅️ panggil fungsi baru
        }


        b.tvUserStatus.setOnClickListener {
            val user = AuthManager.getSavedUser(this)
            if (user == null) {
                // Belum login → buka login
                showLoginDialog()
            } else {
                // Sudah login → buka dialog top up
                showTopUpDialog(user)   // ✅ lempar user
            }
        }
        // 7. Sembunyikan keyboard saat masuk activity
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
    }

    override fun onResume() {
        super.onResume()
        updateBotStatusUI()
        tampilkanInfoProfilTersimpan()
        updateUserStatus()

    }
// update //---
    @RequiresApi(Build.VERSION_CODES.P)
    private fun checkUpdate() {
        val db = FirebaseDatabase.getInstance(
            "https://kecoaxx-db898-default-rtdb.asia-southeast1.firebasedatabase.app"
        )

        db.getReference("update").get().addOnSuccessListener { snap ->
            val latestCode = snap.child("latestVersionCode").getValue(Int::class.java) ?: 0
            val latestName = snap.child("latestVersionName").getValue(String::class.java) ?: "?"
            val apkUrl = snap.child("apkUrl").getValue(String::class.java) ?: ""
            val changelog = snap.child("changelog").getValue(String::class.java) ?: ""

            // ✅ ambil versi aplikasi yg lagi jalan
            val pkgInfo = packageManager.getPackageInfo(packageName, 0)
            val currentCode = pkgInfo.longVersionCode.toInt()
            val currentName = pkgInfo.versionName

            if (latestCode > currentCode) {
                showUpdateDialog(latestName, changelog, apkUrl)
            } else {
                Toast.makeText(this, "✅ Sudah versi terbaru ($currentName)", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener { e ->
            Toast.makeText(this, "❌ Gagal cek update: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showUpdateDialog(latestName: String, changelog: String, apkUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Update tersedia (v$latestName)")
            .setMessage("Changelog:\n$changelog")
            .setPositiveButton("Perbarui") { _, _ ->
                downloadAndInstall(apkUrl)
            }
            .setNegativeButton("Batal", null)
            .show()
    }


    @SuppressLint("Range", "UnspecifiedRegisterReceiverFlag")
    private fun downloadAndInstall(apkUrl: String) {
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Mengunduh kecoaxx...")
            .setDescription("Sedang mengunduh kecoaxx")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setMimeType("application/vnd.android.package-archive")

        // ✅ simpan di folder internal app -> aman di Android 8+
        request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "update_app.apk")

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    unregisterReceiver(this)

                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = dm.query(query)
                    if (cursor.moveToFirst()) {
                        val localUri = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))
                        val file = File(Uri.parse(localUri).path ?: return)

                        // ✅ pake FileProvider biar aman di Android 7+
                        val apkUri = FileProvider.getUriForFile(
                            this@MainActivity,   // atau ganti dengan context lu
                            "${packageName}.provider",
                            file
                        )

                        val installIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(apkUri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(installIntent)
                    }
                    cursor.close()
                }
            }
        }
        registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }


    /** Cek persona tersimpan & accessibility aktif */
    private fun checkPrerequisites(): Boolean {
        val personaOK = PersonaManager.getPersona(this) != null
        val accOK = isServiceEnabled()

        val statusBuilder = StringBuilder()

        // Profile status
        if (personaOK) {
            statusBuilder.append("Checking profile status: [200 ok]\n")
        } else {
            statusBuilder.append("Checking profile status: [null]\n")
        }
        // Accessibility status
        if (accOK) {
            statusBuilder.append("Checking accessibility status:[200 ok]")
        } else {
            statusBuilder.append("Checking accessibility status:[off]")
        }

        b.tvStatus.text = statusBuilder

        if (!personaOK) toast("Isi & save config dulu!")
        if (!accOK) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        return personaOK && accOK
    }

    private fun isServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return list.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    // Presisi: cek flag yang di-set oleh MyBotService
    private fun isMyBotServiceAlive(): Boolean {
        return getSharedPreferences("bot_prefs", MODE_PRIVATE).getBoolean("service_alive", false)
    }

    @SuppressLint("SetTextI18n")
    private fun updateBotStatusUI() {
        val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        val serviceActive = isMyBotServiceAlive()
        botRunning = serviceActive
        if (serviceActive) {
            b.btnStart.text = "Stop Bot"
            b.btnStart.setBackgroundColor("#FF1744".toColorInt())
            if (!b.tvStatus.text.contains("Bot AKTIF")) {
                b.tvStatus.append("\n\uD83D\uDFE2 BOT AKTIF [$currentTime]")
                b.tvStatus.setTextColor("#00E676".toColorInt())
            }
        } else {
            b.btnStart.text = "Start Bot"
            b.btnStart.setBackgroundColor("#00E676".toColorInt())
            if (b.tvStatus.text.contains("Bot AKTIF")) {
                b.tvStatus.append("\n\uD83D\uDD34 BOT BERHENTI [$currentTime]")
            }
        }
    }
    /** Tampilkan info profil tersimpan dalam format yang kamu mau */
    @SuppressLint("SetTextI18n")
    private fun tampilkanInfoProfilTersimpan() {
        val persona = PersonaManager.getPersona(this)
        val modelStr = getSharedPreferences("bot_prefs", MODE_PRIVATE)
            .getString("selected_model", BotPersona.GENZ_CENTIL.name) ?: BotPersona.GENZ_CENTIL.name
        val selectedModel = BotPersona.valueOf(modelStr)

        if (persona == null) {
            b.tvPersonaInfo.text = " ❌ PROFIL KOSONG "
        } else {
            val info = buildString {
                appendLine("🔰 PROFIL TERSIMPAN\nⱭ͞ ̶͞ ̶͞ ̶͞ ̶͞ ̶͞ ̶͞ ̶͞لں͞")
                appendLine("> ${persona.botName}, ${persona.gender}, ${persona.address}, ${persona.hobby}, $selectedModel")
                if (!persona.appName.isNullOrBlank() && !persona.appPackage.isNullOrBlank()) {
                    appendLine("> ${persona.appName} (${persona.appPackage})")
                }
                appendLine("> Blacklist: ${if (persona.blacklist.isNotEmpty()) persona.blacklist.joinToString(", ") else "-"}")

            }
            b.tvPersonaInfo.text = info
        }
    }
    private fun showLoginDialog() {
        val phoneInput = EditText(this).apply { hint = "Nomor HP" }
        val passInput = EditText(this).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            addView(phoneInput)
            addView(passInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Login / Register")
            .setView(layout)
            .setPositiveButton("OK") { dialog, _ ->
                val phone = phoneInput.text.toString().trim()
                val pass = passInput.text.toString().trim()

                lifecycleScope.launch {
                    AuthManager.loginOrRegister(
                        this@MainActivity,
                        phone,
                        pass
                    ) { success, message ->
                        if (success) {
                            toast(message ?: "Login sukses")
                        } else {
                            toast(message ?: "Login gagal")
                        }
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Batal") { d, _ -> d.dismiss() }
            .show()
    }

    private fun showResetPasswordDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Reset Password (OTP Dummy)")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        val etPhone = EditText(this).apply {
            hint = "Nomor HP"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val etNewPass = EditText(this).apply {
            hint = "Password Baru"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        layout.addView(etPhone)
        layout.addView(etNewPass)
        builder.setView(layout)

        builder.setPositiveButton("Reset") { dialog, _ ->
            val phone = etPhone.text.toString().trim()
            val newPass = etNewPass.text.toString().trim()

            if (phone.isEmpty() || newPass.isEmpty()) {
                toast("Isi semua field!")
                return@setPositiveButton
            }

            val success = AuthManager.resetPassword(this, phone, newPass)
            if (success) {
                toast("Password direset ✅ (OTP dummy)")
            } else {
                toast("Nomor tidak terdaftar ❌")
            }

            dialog.dismiss()
        }


        builder.show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
    fun AppCompatActivity.toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("SetTextI18n")
    private fun updateUserStatus() {
        val user = AuthManager.getSavedUser(this)
        if (user == null) {
            b.tvUserStatus.text = "Login"
        } else {
            val maskedPhone = if (user.phone.length > 5) {
                user.phone.take(2) + "***" + user.phone.takeLast(3)
            } else {
                user.phone
            }
            b.tvUserStatus.text = "$maskedPhone | Token: ${user.tokenBalance}"
        }
    }

    private fun showTopUpDialog(user: User) {
        val input = EditText(this).apply {
            hint = "Jumlah token"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        AlertDialog.Builder(this)
            .setTitle("Top Up Token")
            .setMessage("Isi jumlah token yang ingin dibeli untuk ${user.phone}")
            .setView(input)
            .setPositiveButton("OK") { d, _ ->
                val jumlah = input.text.toString().toIntOrNull() ?: 0
                if (jumlah > 0) {
                    AuthManager.updateToken(this, user.phone, jumlah)
                    updateUserStatus()
                    toast("Top up berhasil +$jumlah token")
                } else {
                    toast("Jumlah tidak valid")
                }
                d.dismiss()
            }
            .setNegativeButton("Batal") { d, _ -> d.dismiss() }
            .show()
    }


}
