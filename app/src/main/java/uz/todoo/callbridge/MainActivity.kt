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
import android.os.PowerManager
import android.provider.Settings
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

        binding.btnSave.setOnClickListener { save() }
        binding.btnClearLog.setOnClickListener {
            prefs.clearLog()
            refresh()
            snack(getString(R.string.msg_log_cleared))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
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
                R.id.action_share_log -> { shareLog(); true }
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
        val url = binding.inputUrl.text.toString().trim().trimEnd('/')
        val key = binding.inputKey.text.toString().trim()
        if (url.isBlank() || key.isBlank()) {
            snack(getString(R.string.msg_need_url_key))
            return
        }
        if (!url.startsWith("https://")) {
            snack(getString(R.string.msg_need_https))
            return
        }
        prefs.odooUrl = url
        prefs.apiKey = key
        prefs.deviceName = binding.inputDeviceName.text.toString().trim()

        // Shundan keyin hamma narsa avtomatik.
        CallSyncWorker.schedulePeriodic(this)
        CallSyncWorker.runNow(this)
        prefs.log("Sozlamalar saqlandi — avtomatik rejim yoqildi")
        snack(getString(R.string.msg_saved))
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
                            if (entry.durationSeconds > 0) {
                                RecordingQueue(this).add(odooId, entry.startMillis, entry.endMillis)
                            }
                        }
                    }
                    prefs.lastSyncMillis = System.currentTimeMillis()
                    "Yuborildi: ${entries.first().phoneNumber}"
                } catch (e: Exception) {
                    "Xato: ${e.message}"
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

    private fun shareLog() {
        val text = prefs.readLog().ifBlank { getString(R.string.log_empty) }
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Call Bridge jurnali")
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                getString(R.string.menu_share_log)
            )
        )
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
                    if (prefs.lastError.isNotBlank()) {
                        appendLine()
                        appendLine("Oxirgi xato: ${prefs.lastError}")
                    }
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
            else -> setOverall(R.string.emoji_ok, R.string.state_ready, R.string.state_ready_sub)
        }

        binding.statSent.text = prefs.sentToday.toString()
        binding.statPending.text = pendingCount(callLog).toString()
        binding.statLastSync.text = prefs.lastSyncMillis
            .takeIf { it > 0 }
            ?.let { SimpleDateFormat("HH:mm", Locale.US).format(Date(it)) }
            ?: getString(R.string.dash)

        binding.textLog.text = prefs.readLog().ifBlank { getString(R.string.log_empty) }
    }

    private fun setOverall(emoji: Int, title: Int, subtitle: Int) {
        binding.iconOverall.setText(emoji)
        binding.textOverall.setText(title)
        binding.textOverallSub.setText(subtitle)
    }

    /** Hali Odoo'ga yetib bormagan qo'ng'iroq va audio yozuvlar soni. */
    private fun pendingCount(canReadCallLog: Boolean): Int {
        val calls = if (canReadCallLog) {
            runCatching { CallLogReader(this).readNewerThan(prefs.lastCallLogId).size }.getOrDefault(0)
        } else {
            0
        }
        return calls + RecordingQueue(this).read().size
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
}
