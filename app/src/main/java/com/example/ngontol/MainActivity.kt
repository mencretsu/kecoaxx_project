package com.example.ngontol

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.example.ngontol.auth.LicenseManager
import com.example.ngontol.databinding.ActivityMainBinding
import com.example.ngontol.managers.FakeGpsManager
import com.example.ngontol.managers.PermissionManager
import com.example.ngontol.managers.UpdateManager
import com.google.android.gms.location.LocationServices
import com.jakewharton.threetenabp.AndroidThreeTen
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var fakeGpsManager: FakeGpsManager
    private var currentRelogOption = 3 // default no relog
    private var selectedModel: BotPersona = BotPersona.GENZ_CENTIL
    private var ignoreFakeSpinnerCallback = false
    private val blacklistItems = mutableListOf<String>() // FIX: Simpan blacklist di memory

    // ✅ BroadcastReceiver untuk sync status
    private val botStatusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.ngontol.BOT_STATUS_CHANGED") {
                Log.d("MainActivity", "📡 Bot status changed, updating UI")
                runOnUiThread {
                    updateBotStatus()
                    updateClearButton()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidThreeTen.init(this)

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        // Init managers
        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        fakeGpsManager = FakeGpsManager(this, fusedClient)

        // Check license
        checkLicense()

        // Setup UI
        setupPersonaSpinner()
        setupFakeGpsSpinner()
        setupButtons()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        // ✅ Unregister receiver
        try {
            unregisterReceiver(botStatusReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregister receiver: ${e.message}")
        }
    }

    // ==================== LICENSE ====================

    private fun checkLicense() {
        lifecycleScope.launch {
            val isValid = LicenseManager.validateLicense(this@MainActivity)
            if (!isValid) {
//                showLicenseDialog()
                updateLicenseStatus()
            } else {
                updateLicenseStatus()
            }
        }
    }

    private fun showLicenseDialog() {
        val input = EditText(this).apply {
            hint = "Masukkan kode lisensi"
            setPadding(50, 40, 50, 40)
        }

        AlertDialog.Builder(this)
            .setTitle("🔒 Aktivasi Lisensi")
            .setMessage("Masukkan kode lisensi untuk menggunakan aplikasi")
            .setView(input)
            .setPositiveButton("Aktivasi") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    activateLicense(key)
                } else {
                    toast("Kode lisensi tidak boleh kosong")
                    finish()
                }
            }
            .setNegativeButton("Keluar") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun activateLicense(key: String) {
        lifecycleScope.launch {
            LicenseManager.activateLicense(this@MainActivity, key) { success, message ->
                toast(message)
                if (success) {
                    updateLicenseStatus()
                } else {
                    finish()
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateLicenseStatus() {
        val info = LicenseManager.getLicenseInfo(this)
        if (info != null) {
            val maskedKey = "${info.key.take(4)}****${info.key.takeLast(4)}"
            b.tvUserStatus.text = "👤 ${info.userName} | 🔑 $maskedKey"
        } else {
            b.tvUserStatus.text = "Login"
        }
    }

    // ==================== SETUP UI ====================

    private fun setupPersonaSpinner() {
        val labels = BotPersona.entries.map { it.label }
        val adapter = ArrayAdapter(this, R.layout.spinner_item, labels)
        adapter.setDropDownViewResource(R.layout.spinner_item)
        b.spinnerPersona.adapter = adapter

        // Load saved selection
        val prefs = getSharedPreferences("bot_prefs", MODE_PRIVATE)
        val savedModel = prefs.getString("selected_model", BotPersona.GENZ_CENTIL.name)
        val index = BotPersona.entries.indexOfFirst { it.name == savedModel }
        if (index >= 0) {
            b.spinnerPersona.setSelection(index)
            selectedModel = BotPersona.entries[index]
        }

        b.spinnerPersona.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            @SuppressLint("UseKtx")
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, pos: Int, id: Long) {
                selectedModel = BotPersona.entries[pos]
                prefs.edit().putString("selected_model", selectedModel.name).apply()
                updatePersonaInfo()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupFakeGpsSpinner() {
        val cityNames = fakeGpsManager.cities.map { it.name }
        val adapter = ArrayAdapter(this, R.layout.spinner_item, cityNames)
        adapter.setDropDownViewResource(R.layout.spinner_item)
        b.spinnerFakeGps.adapter = adapter

        // Load saved
        val savedPos = fakeGpsManager.getSavedSelection()
        ignoreFakeSpinnerCallback = true
        b.spinnerFakeGps.setSelection(savedPos)

        b.spinnerFakeGps.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, pos: Int, id: Long) {
                if (ignoreFakeSpinnerCallback) {
                    ignoreFakeSpinnerCallback = false
                    return
                }
                handleFakeGpsChange(pos)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun setupButtons() {
        b.btnConfig.setOnClickListener {
            showPersonaForm()
        }

        b.etTargetName.apply {
            isFocusable = false
            isClickable = true
            setOnClickListener { showAppPicker() }
        }

        b.btnSavePersona.setOnClickListener {
            savePersona()
        }

        b.btnStart.setOnClickListener {
            toggleBot()
        }

        b.btnClear.setOnClickListener {
            openAppSettings()
        }

        b.btnCheckUpdate.setOnClickListener {
            checkUpdate()
        }

        b.tvUserStatus.setOnClickListener {
            showLicenseInfo()
        }

        b.btndevOpt.setOnClickListener {
            openDevOptions()
        }

        b.rgRelogOption.setOnCheckedChangeListener { _, checkedId ->
            currentRelogOption = when (checkedId) {
                R.id.rbRelogAll -> 1
                R.id.rbRelogUnread -> 2
                R.id.rbNoRelog -> 3
                else -> 3
            }
        }
        b.btnAddBlacklist.setOnClickListener {
            addBlacklistItem()
        }

        b.btnkeyboardSett.setOnClickListener {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        // ✅ Register broadcast receiver
        val filter = android.content.IntentFilter("com.example.ngontol.BOT_STATUS_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(botStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(botStatusReceiver, filter)
        }
    }

    // ==================== BLACKLIST MANAGEMENT ====================

    private fun addBlacklistItem() {
        val newItem = b.etNewBlacklistItem.text.toString().trim()
        if (newItem.isNotBlank()) {
            if (!blacklistItems.contains(newItem)) {
                blacklistItems.add(newItem)
                updateBlacklistUI()
                b.etNewBlacklistItem.setText("")
                toast("✅ $newItem ditambahkan ke blacklist")
            } else {
                toast("❌ Item sudah ada di blacklist")
            }
        } else {
            toast("❌ Masukkan item terlebih dahulu")
        }
    }

    private fun removeBlacklistItem(item: String) {
        blacklistItems.remove(item)
        updateBlacklistUI()
        toast("🗑️ $item dihapus dari blacklist")
    }

    @SuppressLint("SetTextI18n")
    private fun updateBlacklistUI() {
        b.blacklistContainer.removeAllViews()

        if (blacklistItems.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "Tidak ada item blacklist"
                setTextColor("#666666".toColorInt())
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, 8, 0, 8)
            }
            b.blacklistContainer.addView(emptyView)
            return
        }

        for (item in blacklistItems) {
            val itemLayout = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }

            val tvItem = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                text = "• $item"
                setTextColor("#00FF66".toColorInt())
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
            }

            val btnRemove = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = "HAPUS"
                setTextColor("#FF5252".toColorInt())
                background = resources.getDrawable(R.drawable.button_background, null)
                textSize = 10f
                setPadding(16, 8, 16, 8)
                setOnClickListener { removeBlacklistItem(item) }
            }

            itemLayout.addView(tvItem)
            itemLayout.addView(btnRemove)
            b.blacklistContainer.addView(itemLayout)
        }
    }

    // ==================== PERSONA FORM ====================

    @SuppressLint("UseKtx")
    private fun showPersonaForm() {
        val persona = PersonaManager.getPersona(this)

        // FIX: Clear blacklist items sebelum load data
        blacklistItems.clear()

        // Set nilai form
        persona?.let {
            b.etBotName.setText(it.botName)
            b.etGender.setText(it.gender)
            b.etAddress.setText(it.address)
            b.etHobby.setText(it.hobby)

            // FIX: Load blacklist dari data lama SETELAH clear
            it.blacklist?.let { oldBlacklist ->
                blacklistItems.addAll(oldBlacklist)
            }
            updateBlacklistUI()

            // SET RADIO BUTTON BERDASARKAN RELOG OPTION YANG DISIMPAN
            val radioId = when (it.relogOption) {
                1 -> R.id.rbRelogAll
                2 -> R.id.rbRelogUnread
                3 -> R.id.rbNoRelog
                else -> R.id.rbNoRelog
            }
            b.rgRelogOption.check(radioId)

            // Update currentRelogOption juga
            currentRelogOption = it.relogOption ?: 3

            // Set app name jika ada
            if (!it.appName.isNullOrBlank()) {
                b.etTargetName.setText(it.appName)
            }
        } ?: run {
            // Jika persona null, set default values
            b.etBotName.setText("")
            b.etGender.setText("")
            b.etAddress.setText("")
            b.etHobby.setText("")
            b.rgRelogOption.check(R.id.rbNoRelog)
            currentRelogOption = 3
            b.etTargetName.setText("")
        }

        // Clear new item input
        b.etNewBlacklistItem.setText("")

        b.panelForm.visibility = View.VISIBLE
        b.btnConfig.visibility = View.GONE
    }

    // ==================== UI ACTIONS ====================

    private fun showAppPicker() {
        val apps = listOf(
            "Sugo" to "com.voicemaker.android",
            "Timo" to "com.hwsj.club",
            "Sugo Lite" to "com.fiya.android"
        )

        val names = apps.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Pilih Aplikasi")
            .setItems(names) { _, which ->
                val (name, pkg) = apps[which]
                b.etTargetName.setText(name)

                getSharedPreferences("bot_prefs", MODE_PRIVATE).edit().apply {
                    putString("last_app_name", name)
                    putString("last_app_package", pkg)
                    apply()
                }

                toast("✅ $name dipilih")
            }
            .show()
    }

    private fun savePersona() {
        val prefs = getSharedPreferences("bot_prefs", MODE_PRIVATE)
        val appName = prefs.getString("last_app_name", null)
        val appPackage = prefs.getString("last_app_package", null)

        val oldPersona = PersonaManager.getPersona(this)

        // FIX: Gunakan blacklistItems dari UI, bukan dari oldPersona
        val relogOption = currentRelogOption

        val persona = Persona(
            botName = b.etBotName.text.toString().ifBlank { oldPersona?.botName ?: "" },
            gender = b.etGender.text.toString().ifBlank { oldPersona?.gender ?: "" },
            address = b.etAddress.text.toString().ifBlank { oldPersona?.address ?: "" },
            hobby = b.etHobby.text.toString().ifBlank { oldPersona?.hobby ?: "" },
            blacklist = blacklistItems.toList(), // FIX: Pakai blacklist dari UI
            appName = appName ?: oldPersona?.appName,
            appPackage = appPackage ?: oldPersona?.appPackage,
            relogOption = relogOption
        )

        if (persona.botName.isBlank()) {
            toast("Isi nama dulu!")
            return
        }
        if (persona.appPackage.isNullOrBlank() || persona.appName.isNullOrBlank()) {
            toast("Pilih aplikasi dulu!")
            return
        }

        PersonaManager.savePersona(this, persona)
        toast("✅ Profil tersimpan")

        b.panelForm.visibility = View.GONE
        b.btnConfig.visibility = View.VISIBLE
        updatePersonaInfo()
        b.btnStart.isEnabled = true
    }

    private fun toggleBot() {
        val serviceActive = MyBotService.isServiceActive()

        if (!serviceActive) {
            if (!checkPrerequisites()) return
            startBot()
        } else {
            stopBot()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun startBot() {
        val fakeGpsPos = b.spinnerFakeGps.selectedItemPosition
        if (fakeGpsPos > 0) {
            val intent = Intent(this, FakeGpsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        val intent = Intent(this, MyBotService::class.java)
        startService(intent)

        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        b.btnStart.text = "Stop Bot"
        b.btnStart.setBackgroundColor("#FF1744".toColorInt())
        b.tvStatus.text= "\uD83D\uDFE2 BOT AKTIF [$time]"
    }

    @SuppressLint("SetTextI18n")
    private fun stopBot() {
        val stopIntent = Intent(this, MyBotService::class.java).apply {
            action = MyBotService.ACTION_STOP
        }
        startService(stopIntent)

        stopService(Intent(this, FakeGpsService::class.java))

        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        b.btnStart.text = "Start Bot"
        b.btnStart.setBackgroundColor("#00E676".toColorInt())
        b.tvStatus.text= "\uD83D\uDD34 BOT BERHENTI [$time]"
    }

    @SuppressLint("UseKtx")
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:com.example.ngontol")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(intent)
            toast("Membuka info aplikasi...")
        } catch (e: Exception) {
            toast("Gagal: ${e.message}")
        }
    }

    private fun checkUpdate() {
        UpdateManager.checkForUpdate(this) {
            toast("✅ Sudah versi terbaru")
        }
    }

    private fun showLicenseInfo() {
        val info = LicenseManager.getLicenseInfo(this)
        if (info == null) {
            showLicenseDialog()
            return
        }

        val message = """
            👤 User: ${info.userName}
            🔑 Key: ${info.key}
            📅 Expired: ${info.expiryDate ?: "Lifetime"}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Info Lisensi")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("Logout") { _, _ ->
                logout()
            }
            .show()
    }

    private fun logout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Yakin ingin logout?")
            .setPositiveButton("Ya") { _, _ ->
                LicenseManager.logout(this)
                toast("Logout berhasil")
                finish()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ==================== FAKE GPS ====================

    @SuppressLint("MissingPermission")
    private fun handleFakeGpsChange(position: Int) {
        if (!PermissionManager.hasLocationPermission(this)) {
            toast("Butuh izin lokasi")
            resetFakeGpsSpinner()
            PermissionManager.requestLocationPermission(this)
            return
        }

        if (!PermissionManager.isLocationEnabled(this)) {
            toast("Aktifkan GPS terlebih dahulu")
            resetFakeGpsSpinner()
            PermissionManager.openLocationSettings(this)
            return
        }

        if (!PermissionManager.isMockLocationEnabled(this) && position > 0) {
            toast("Aktifkan Mock Location di Developer Options")
            resetFakeGpsSpinner()
            PermissionManager.openDeveloperSettings(this)
            return
        }

        lifecycleScope.launch {
            fakeGpsManager.enableFakeGps(
                position,
                onSuccess = { city, lat, lng ->
                    if (position > 0) {
                        toast("✅ Fake GPS: $city\n(${"%.4f".format(lat)}, ${"%.4f".format(lng)})")
                    } else {
                        toast("Fake GPS: OFF")
                    }
                    updatePersonaInfo()
                },
                onError = { error ->
                    toast(error)
                    resetFakeGpsSpinner()
                }
            )
        }
    }

    private fun resetFakeGpsSpinner() {
        runOnUiThread {
            ignoreFakeSpinnerCallback = true
            b.spinnerFakeGps.setSelection(fakeGpsManager.getSavedSelection())
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionManager.REQUEST_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                toast("Permission diterima")
            } else {
                toast("Permission ditolak")
                resetFakeGpsSpinner()
            }
        }
    }

    // ==================== UI UPDATE ====================

    @SuppressLint("SetTextI18n")
    private fun updateUI() {
        updatePersonaInfo()
        updateBotStatus()
        updateLicenseStatus()
        updateClearButton()

        val personaOK = PersonaManager.getPersona(this) != null
        b.btnStart.isEnabled = personaOK

        ignoreFakeSpinnerCallback = true
        b.spinnerFakeGps.setSelection(fakeGpsManager.getSavedSelection())
    }

    @SuppressLint("SetTextI18n")
    private fun updatePersonaInfo() {
        val persona = PersonaManager.getPersona(this)
        val modelStr = getSharedPreferences("bot_prefs", MODE_PRIVATE)
            .getString("selected_model", BotPersona.GENZ_CENTIL.name) ?: BotPersona.GENZ_CENTIL.name
        val selectedModel = BotPersona.valueOf(modelStr)
        val cityName = fakeGpsManager.getSavedCityName()

        if (persona == null) {
            b.tvPersonaInfo.text = "❌ PROFIL KOSONG"
        } else {
            val info = buildString {
                appendLine("📰 PROFIL TERSIMPAN")
                appendLine("⸻⸻⸻⸻⸻⸻⸻⸻")
                appendLine("> ${persona.botName}, ${persona.gender}, ${persona.address}, ${persona.hobby}, $selectedModel")
                if (!persona.appName.isNullOrBlank()) {
                    appendLine("> ${persona.appName} (${persona.appPackage})")

                    val relogText = when (persona.relogOption) {
                        1 -> "Semua"
                        2 -> "Belum Dibaca"
                        3 -> "No Relog"
                        else -> "Tidak Diketahui"
                    }

                    appendLine("> Relog: $relogText")
                }

                appendLine("> Blacklist: ${if (persona.blacklist.isNotEmpty()) persona.blacklist.joinToString(", ") else "-"}")
                appendLine("> Fake GPS: $cityName")
            }
            b.tvPersonaInfo.text = info
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateBotStatus() {
        val serviceActive = MyBotService.isServiceActive()
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        if (serviceActive) {
            b.btnStart.text = "Stop Bot"
            b.btnStart.setBackgroundColor("#FF1744".toColorInt())
            if (!b.tvStatus.text.contains("BOT AKTIF")) {
                b.tvStatus.append("\n🟢 BOT AKTIF [$time]")
                b.tvStatus.setTextColor("#00E676".toColorInt())
            }
        } else {
            b.btnStart.text = "Start Bot"
            b.btnStart.setBackgroundColor("#00E676".toColorInt())
            if (b.tvStatus.text.contains("BOT AKTIF")) {
                b.tvStatus.append("\n🔴 BOT BERHENTI [$time]")
                b.tvStatus.setTextColor("#FF1744".toColorInt())

            }
        }
    }

    private fun updateClearButton() {
        val hasProfileData = PersonaManager.getPersona(this) != null
        val hasBotPrefs = getSharedPreferences("bot_prefs", MODE_PRIVATE).all.isNotEmpty()
        val hasAnyData = hasProfileData || hasBotPrefs
        val botRunning = MyBotService.isServiceActive()

        b.btnClear.isEnabled = !botRunning && hasAnyData
        b.btnClear.alpha = if (b.btnClear.isEnabled) 1.0f else 0.5f
    }

    // ==================== UTILS ====================

    private fun checkPrerequisites(): Boolean {
        val personaOK = PersonaManager.getPersona(this) != null
        val accOK = isAccessibilityEnabled()

        val status = buildString {
            append("Profile: ")
            appendLine(if (personaOK) "[200 OK]" else "[NULL]")
            append("Accessibility: ")
            append(if (accOK) "[200 OK]" else "[OFF]")
        }

        b.tvStatus.text = status

        if (!personaOK) {
            toast("Isi & save config dulu!")
            return false
        }

        if (!accOK) {
            toast("Aktifkan Accessibility Service")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return false
        }

        return true
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        val list = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return list.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("HardwareIds")
    private fun openDevOptions() {
        try {
            val devOptionEnabled = try {
                android.provider.Settings.Global.getInt(
                    contentResolver,
                    android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED
                ) == 1
            } catch (e: Exception) {
                false
            }

            val intent: Intent

            if (devOptionEnabled) {
                intent = Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            } else {
                intent = Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS)
                Toast.makeText(
                    this,
                    "Tap: Nomor versi/Build number/nomor build 7x.",
                    Toast.LENGTH_LONG
                ).show()
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)

        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent("android.settings.MY_DEVICE_INFO_SETTINGS")
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(fallbackIntent)
                Toast.makeText(
                    this,
                    "Tap: Nomor versi/Build number/nomor build 7x.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e2: Exception) {
                val lastIntent = Intent(android.provider.Settings.ACTION_SETTINGS)
                lastIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(lastIntent)
            }
        }
    }
}