package uz.todoo.callbridge

import android.content.Context
import android.provider.Settings
import java.util.UUID

/** Sozlamalar va sinxronizatsiya holati (SharedPreferences). */
class Prefs(context: Context) {

    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences("call_bridge", Context.MODE_PRIVATE)

    var odooUrl: String
        get() = sp.getString(KEY_URL, "").orEmpty()
        set(value) = sp.edit().putString(KEY_URL, value.trim().trimEnd('/')).apply()

    var apiKey: String
        get() = sp.getString(KEY_API, "").orEmpty()
        set(value) = sp.edit().putString(KEY_API, value.trim()).apply()

    /** Odoo'da guruhlash uchun ko'rinadigan nom, masalan "Ozodbek — Samsung S23+". */
    var deviceName: String
        get() = sp.getString(KEY_DEVICE_NAME, "").orEmpty()
        set(value) = sp.edit().putString(KEY_DEVICE_NAME, value.trim()).apply()

    /** Oxirgi muvaffaqiyatli yuborilgan CallLog._ID. */
    var lastCallLogId: Long
        get() = sp.getLong(KEY_LAST_ID, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_ID, value).apply()

    /** Audio yozuvlar topilgan papka — birinchi muvaffaqiyatdan keyin eslab qolinadi. */
    var recordingDir: String?
        get() = sp.getString(KEY_REC_DIR, null)
        set(value) = sp.edit().putString(KEY_REC_DIR, value).apply()

    /** Qurilmaning barqaror identifikatori. */
    val deviceId: String
        get() {
            sp.getString(KEY_DEVICE, null)?.let { return it }
            val android = Settings.Secure.getString(
                appContext.contentResolver, Settings.Secure.ANDROID_ID
            )
            val id = if (android.isNullOrBlank()) UUID.randomUUID().toString() else android
            sp.edit().putString(KEY_DEVICE, id).apply()
            return id
        }

    val isConfigured: Boolean
        get() = odooUrl.isNotBlank() && apiKey.isNotBlank()

    // --- Status jurnali (adb yo'q, shuning uchun app ichida ko'rsatamiz) ---

    fun log(line: String) {
        val stamp = android.text.format.DateFormat.format("MM-dd HH:mm:ss", System.currentTimeMillis())
        val existing = sp.getString(KEY_LOG, "").orEmpty()
        val updated = "[$stamp] $line\n$existing".take(MAX_LOG_CHARS)
        sp.edit().putString(KEY_LOG, updated).apply()
    }

    fun readLog(): String = sp.getString(KEY_LOG, "").orEmpty()

    fun clearLog() = sp.edit().remove(KEY_LOG).apply()

    private companion object {
        const val KEY_URL = "odoo_url"
        const val KEY_API = "api_key"
        const val KEY_DEVICE_NAME = "device_name_label"
        const val KEY_LAST_ID = "last_call_log_id"
        const val KEY_REC_DIR = "recording_dir"
        const val KEY_DEVICE = "device_id"
        const val KEY_LOG = "status_log"
        const val MAX_LOG_CHARS = 20_000
    }
}
