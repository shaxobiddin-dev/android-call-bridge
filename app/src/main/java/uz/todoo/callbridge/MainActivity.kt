package uz.todoo.callbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.snackbar.Snackbar
import uz.todoo.callbridge.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    /** Ekran ochiq turganda holat o'zi yangilanadi — qo'lda qayta ochish shart emas. */
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ticker.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        applyWindowInsets()
        setUpToolbar()
        setUpPermissionRows()

        binding.inputUrl.setText(prefs.odooUrl)
        binding.inputKey.setText(prefs.apiKey)
        binding.inputDeviceName.setText(prefs.deviceName)

        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateSaveButton()
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
        }
        binding.inputUrl.addTextChangedListener(watcher)
        binding.inputKey.addTextChangedListener(watcher)
        binding.inputDeviceName.addTextChangedListener(watcher)
        updateSaveButton()

        binding.btnSave.setOnClickListener { save() }
        binding.btnResend.setOnClickListener { resendMissing() }
    }

    override fun onResume() {
        super.onResume()
        PinSession.takeFormResetMessage()?.let { message ->
            binding.inputUrl.setText(prefs.odooUrl)
            binding.inputKey.setText(prefs.apiKey)
            snack(getString(message))
        }
        if (askPinIfNeeded()) return
        PinSession.onForeground()
        refresh()
        ticker.postDelayed(tick, REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    override fun onStop() {
        super.onStop()
        PinSession.onBackground()
    }

    /**
     * Sozlangan ilova PIN'siz ochilmaydi. Birinchi sozlashda kiritiladigan
     * kalit hali yo'q, shuning uchun forma ochiq turadi.
     *
     * @return true bo'lsa PIN ekrani ochildi, asosiy ekran yashirin qoladi
     */
    private fun askPinIfNeeded(): Boolean {
        val needed = prefs.isConfigured && !PinSession.isUnlocked()
        binding.root.visibility = if (needed) View.INVISIBLE else View.VISIBLE
        if (needed) startActivity(Intent(this, PinActivity::class.java))
        return needed
    }

    /**
     * Saqlash tugmasi faqat o'zgarish bo'lganda yonadi. Saqlangandan keyin
     * o'chadi — bosishga hojat yo'qligi shundan bilinadi.
     */
    private fun updateSaveButton() {
        val changed = Prefs.normalizeUrl(binding.inputUrl.text.toString()) != prefs.odooUrl ||
            binding.inputKey.text.toString().trim() != prefs.apiKey ||
            binding.inputDeviceName.text.toString().trim() != prefs.deviceName
        binding.btnSave.isEnabled = changed
    }

    /**
     * Android 15+ da oyna to'liq ekranga cho'ziladi. Insets qo'llanmasa
     * sarlavha status bar ostida qolib ketadi — avvalgi versiyadagi buzilish
     * aynan shundan edi.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, bars.bottom)
            insets
        }
    }

    private fun setUpToolbar() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sync -> {
                    CallSyncWorker.runNow(this)
                    snack(getString(R.string.msg_sync_started))
                    true
                }
                R.id.action_test -> { sendLastCallNow(); true }
                R.id.action_about -> { showAbout(); true }
                else -> false
            }
        }
    }

    // ------------------------------------------------------------------
    // Ruxsat qatorlari
    // ------------------------------------------------------------------

    private fun setUpPermissionRows() {
        bindRow(binding.rowCallLog.root, R.string.perm_calllog_title, R.string.perm_calllog_sub) {
            requestRuntimePermissions()
        }
        bindRow(binding.rowPhoneState.root, R.string.perm_phone_title, R.string.perm_phone_sub) {
            requestRuntimePermissions()
        }
        bindRow(binding.rowFiles.root, R.string.perm_files_title, R.string.perm_files_sub) {
            openAllFilesAccess()
        }
        bindRow(binding.rowBattery.root, R.string.perm_battery_title, R.string.perm_battery_sub) {
            openBatterySettings()
        }
    }

    private fun bindRow(row: View, title: Int, subtitle: Int, onClick: () -> Unit) {
        row.findViewById<TextView>(R.id.rowTitle).setText(title)
        row.findViewById<TextView>(R.id.rowSubtitle).setText(subtitle)
        row.setOnClickListener { onClick() }
    }

    private fun paintRow(row: View, granted: Boolean) {
        val pill = row.findViewById<TextView>(R.id.rowPill)
        pill.setText(if (granted) R.string.pill_ok else R.string.pill_bad)
        pill.setTextColor(
            ContextCompat.getColor(this, if (granted) R.color.status_ok else R.color.status_bad)
        )
    }

    // ------------------------------------------------------------------
    // Amallar
    // ------------------------------------------------------------------

    private fun save() {
        val url = Prefs.normalizeUrl(binding.inputUrl.text.toString())
        val key = binding.inputKey.text.toString().trim()
        if (url.isBlank() || key.isBlank()) {
            snack(getString(R.string.msg_need_url_key))
            return
        }
        if (!url.startsWith("https://")) {
            snack(getString(R.string.msg_need_https))
            return
        }
        // Boshqa kalit — boshqa xodim, shuning uchun PIN qaytadan so'raladi
        if (url != prefs.odooUrl || key != prefs.apiKey) {
            prefs.pinVerified = false
            PinSession.lock()
        }
        prefs.odooUrl = url
        prefs.apiKey = key
        binding.inputUrl.setText(url)
        prefs.deviceName = binding.inputDeviceName.text.toString().trim()

        // Shundan keyin hamma narsa avtomatik.
        CallSyncWorker.schedulePeriodic(this)
        CallSyncWorker.runNow(this)
        prefs.log("Sozlamalar saqlandi — avtomatik rejim yoqildi")
        snack(getString(R.string.msg_saved))
        updateSaveButton()
        if (askPinIfNeeded()) return
        refresh()
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
            needed += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    /** OEM dialer yozuvlari MediaStore'dan tashqarida bo'lishi mumkin. */
    private fun openAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requestRuntimePermissions()
            return
        }
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    /** Bunisiz Xiaomi/Samsung ilovani fonda o'ldiradi. */
    private fun openBatterySettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    /** Sinov: oxirgi qo'ng'iroqni darhol yuborish. */
    private fun sendLastCallNow() {
        if (!prefs.isConfigured) {
            snack(getString(R.string.msg_need_url_key))
            return
        }
        if (!has(Manifest.permission.READ_CALL_LOG)) {
            requestRuntimePermissions()
            return
        }
        val reader = CallLogReader(this)
        val latest = reader.latestId()
        if (latest == 0L) {
            snack("Qo'ng'iroq jurnali bo'sh")
            return
        }
        Thread {
            val entries = reader.readNewerThan(latest - 1)
            val message = if (entries.isEmpty()) {
                "Qo'ng'iroq topilmadi"
            } else {
                try {
                    val ids = OdooApi(prefs.odooUrl, prefs.apiKey)
                        .push(prefs.deviceId, entries, prefs.deviceName)
                    entries.first().let { entry ->
                        ids[entry.deviceCallId]?.let { odooId ->
                            if (entry.expectsRecording) {
                                RecordingQueue(this).add(odooId, entry.startMillis, entry.endMillis)
                            }
                        }
                    }
                    prefs.lastSyncMillis = System.currentTimeMillis()
                    "Yuborildi: ${entries.first().phoneNumber}"
                } catch (e: Exception) {
                    prefs.log("Sinov yuborilmadi: ${e.message}")
                    getString(R.string.msg_test_failed)
                }
            }
            prefs.log(message)
            CallSyncWorker.runNow(this)
            runOnUiThread {
                snack(message)
                refresh()
            }
        }.start()
    }

    /**
     * Odoo'ga yetib bormagan hamma narsani qayta yuboradi.
     *
     * Ikki bo'shliqni yopadi:
     *  1. Qo'ng'iroqning O'ZI tushmagan — jurnal kursorini orqaga suramiz,
     *     server dublikatni o'zi ajratadi.
     *  2. Qo'ng'iroq bor, AUDIOSI yo'q — bayroq qo'yamiz, worker Odoo'ning
     *     o'zidan "audiosi kelmaganlar" ro'yxatini so'raydi. Ilova qayta
     *     o'rnatilgan yoki 24 soatlik urinish muddati tugagan bo'lsa ham
     *     shu yo'l bilan tiklanadi.
     */
    private fun resendMissing() {
        if (!prefs.isConfigured) {
            snack(getString(R.string.msg_need_url_key))
            return
        }
        if (!has(Manifest.permission.READ_CALL_LOG)) {
            requestRuntimePermissions()
            return
        }

        val since = System.currentTimeMillis() - CallSyncWorker.MAX_AGE_MS
        val firstId = CallLogReader(this).firstIdSince(since)
        if (firstId > 0) {
            // Faqat ORQAGA suramiz — oldinga hech qachon
            prefs.lastCallLogId = minOf(prefs.lastCallLogId, maxOf(0L, firstId - 1))
        }
        // Xato bilan "band" deb belgilangan fayllar ham qayta ko'riladi —
        // takrorlanishdan serverdagi checksum himoyasi saqlaydi
        prefs.clearUploaded()
        prefs.resendRequested = true

        CallSyncWorker.runNow(this)
        prefs.log("Qayta yuborish so'raldi (jurnal ID >= $firstId)")
        snack(getString(R.string.msg_resend_started))
        refresh()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(
                buildString {
                    appendLine("Versiya ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine()
                    appendLine("Qurilma ID: ${prefs.deviceId}")
                    appendLine("Oxirgi jurnal ID: ${prefs.lastCallLogId}")
                    prefs.recordingDir?.let { appendLine("Yozuv papkasi: $it") }
                }
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Ekranni yangilash
    // ------------------------------------------------------------------

    private fun refresh() {
        val callLog = has(Manifest.permission.READ_CALL_LOG)
        val phoneState = has(Manifest.permission.READ_PHONE_STATE)
        val files = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            has(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val battery = (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)

        paintRow(binding.rowCallLog.root, callLog)
        paintRow(binding.rowPhoneState.root, phoneState)
        paintRow(binding.rowFiles.root, files)
        paintRow(binding.rowBattery.root, battery)

        val ready = prefs.isConfigured && callLog && phoneState && battery
        when {
            !ready -> setOverall(R.string.emoji_bad, R.string.state_setup, R.string.state_setup_sub)
            !isOnline() -> setOverall(R.string.emoji_wait, R.string.state_offline, R.string.state_offline_sub)
            prefs.lastError.isNotBlank() -> setOverall(R.string.emoji_bad, R.string.state_error, R.string.state_error_sub)
            else -> setOverall(R.string.emoji_ok, R.string.state_ready, R.string.state_ready_sub)
        }

        binding.statSent.text = prefs.sentToday.toString()
        binding.statPending.text = pendingCount(callLog).toString()
        binding.statLastSync.text = prefs.lastSyncMillis
            .takeIf { it > 0 }
            ?.let { SimpleDateFormat("HH:mm", Locale.US).format(Date(it)) }
            ?: getString(R.string.dash)

    }

    private fun setOverall(emoji: Int, title: Int, subtitle: Int) {
        binding.iconOverall.setText(emoji)
        binding.textOverall.setText(title)
        binding.textOverallSub.setText(subtitle)
    }

    /**
     * Hali Odoo'ga yetib bormagan qo'ng'iroq va audio yozuvlar soni.
     *
     * Worker bilan BIR XIL qoidalarni qo'llaydi: birinchi ishga tushishda
     * faqat oxirgi bir nechtasi, va 7 kundan eskilari hisobga olinmaydi.
     * Aks holda toza o'rnatishda "200" kabi qo'rqinchli raqam ko'rinardi.
     */
    private fun pendingCount(canReadCallLog: Boolean): Int {
        val recordings = RecordingQueue(this).read().size
        if (!canReadCallLog) return recordings

        return runCatching {
            val reader = CallLogReader(this)
            val cursor = if (prefs.lastCallLogId == 0L) {
                maxOf(0L, reader.latestId() - CallSyncWorker.FIRST_RUN_LIMIT)
            } else {
                prefs.lastCallLogId
            }
            val oldestAllowed = System.currentTimeMillis() - CallSyncWorker.MAX_AGE_MS
            reader.readNewerThan(cursor).count { it.startMillis >= oldestAllowed } + recordings
        }.getOrDefault(recordings)
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun snack(text: String) =
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()

    private companion object {
        /** Ekran ochiq turganda holat shu oraliqda qayta hisoblanadi. */
        const val REFRESH_INTERVAL_MS = 3_000L
    }
}
