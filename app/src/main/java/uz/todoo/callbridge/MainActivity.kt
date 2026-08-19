package uz.todoo.callbridge

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import uz.todoo.callbridge.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isEmpty()) {
            toast("Ruxsatlar berildi")
        } else {
            toast("Berilmadi: ${denied.joinToString { it.substringAfterLast('.') }}")
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.inputUrl.setText(prefs.odooUrl)
        binding.inputKey.setText(prefs.apiKey)

        binding.btnSave.setOnClickListener { save() }
        binding.btnPermissions.setOnClickListener { requestPermissions() }
        binding.btnAllFiles.setOnClickListener { openAllFilesAccess() }
        binding.btnBattery.setOnClickListener { openBatterySettings() }
        binding.btnSyncNow.setOnClickListener {
            CallSyncWorker.runNow(this)
            toast("Yuborish boshlandi")
        }
        binding.btnTestLast.setOnClickListener { sendLastCallNow() }
        binding.btnClearLog.setOnClickListener {
            prefs.clearLog()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun save() {
        val url = binding.inputUrl.text.toString().trim()
        val key = binding.inputKey.text.toString().trim()
        if (url.isBlank() || key.isBlank()) {
            toast("URL va API key to'ldirilishi kerak")
            return
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            toast("URL https:// bilan boshlanishi kerak")
            return
        }
        prefs.odooUrl = url
        prefs.apiKey = key

        // Birinchi sozlashda eski qo'ng'iroqlarni to'kib yubormaymiz —
        // faqat shu paytdan keyingilari yuboriladi
        if (prefs.lastCallLogId == 0L && hasPermission(Manifest.permission.READ_CALL_LOG)) {
            prefs.lastCallLogId = CallLogReader(this).latestId()
        }

        CallSyncWorker.schedulePeriodic(this)
        prefs.log("Sozlamalar saqlandi")
        toast("Saqlandi")
        refresh()
    }

    private fun requestPermissions() {
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
            toast("Bu Android versiyasida kerak emas")
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
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
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
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** Sinov: oxirgi qo'ng'iroqni darhol yuborish (kutmasdan). */
    private fun sendLastCallNow() {
        if (!prefs.isConfigured) {
            toast("Avval URL va API key saqlang")
            return
        }
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) {
            toast("READ_CALL_LOG ruxsati yo'q")
            return
        }
        val reader = CallLogReader(this)
        val latest = reader.latestId()
        if (latest == 0L) {
            toast("Qo'ng'iroq jurnali bo'sh")
            return
        }
        Thread {
            val entries = reader.readNewerThan(latest - 1)
            if (entries.isEmpty()) {
                runOnUiThread { toast("Qo'ng'iroq topilmadi") }
                return@Thread
            }
            val api = OdooApi(prefs.odooUrl, prefs.apiKey)
            val message = try {
                val ids = api.push(prefs.deviceId, entries)
                val entry = entries.first()
                ids[entry.deviceCallId]?.let { odooId ->
                    if (entry.durationSeconds > 0) {
                        RecordingQueue(this).add(odooId, entry.startMillis, entry.endMillis)
                    }
                }
                "Yuborildi: ${entries.first().phoneNumber} -> Odoo id ${ids.values.firstOrNull()}"
            } catch (e: Exception) {
                "Xato: ${e.message}"
            }
            prefs.log(message)
            runOnUiThread {
                toast(message)
                refresh()
            }
        }.start()
    }

    private fun refresh() {
        val callLog = hasPermission(Manifest.permission.READ_CALL_LOG)
        val phone = hasPermission(Manifest.permission.READ_PHONE_STATE)
        val allFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val battery = (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)

        binding.textStatus.text = buildString {
            appendLine("Sozlangan: ${mark(prefs.isConfigured)}")
            appendLine("Qo'ng'iroq jurnali: ${mark(callLog)}")
            appendLine("Telefon holati: ${mark(phone)}")
            appendLine("Fayllarga kirish (audio): ${mark(allFiles)}")
            appendLine("Batareya cheklovi o'chirilgan: ${mark(battery)}")
            appendLine("Qurilma ID: ${prefs.deviceId}")
            appendLine("Oxirgi yuborilgan jurnal ID: ${prefs.lastCallLogId}")
            prefs.recordingDir?.let { appendLine("Yozuv papkasi: $it") }
        }
        binding.textLog.text = prefs.readLog().ifBlank { "— hali yozuv yo'q —" }
    }

    private fun mark(ok: Boolean) = if (ok) "✅" else "❌"

    private fun hasPermission(permission: String) =
        checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
