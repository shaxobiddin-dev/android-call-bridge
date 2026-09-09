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

    /**
     * Allaqachon Odoo'ga yuklangan audio fayllar.
     *
     * Bitta fayl FAQAT bitta qo'ng'iroqqa tegishli. Ketma-ket qilingan ikki
     * qo'ng'iroqning vaqt oynasi kesishishi mumkin, shuning uchun fayl
     * band bo'lgach ro'yxatdan chiqariladi — aks holda ikkinchi qo'ng'iroqqa
     * ham o'sha fayl biriktirilib, dublikat paydo bo'ladi.
     */
    fun isUploaded(path: String): Boolean = uploadedPaths().contains(path)


    fun markUploaded(path: String) {
        val paths = uploadedPaths().toMutableList()
        if (paths.contains(path)) return
        paths += path
        val array = org.json.JSONArray()
        for (item in paths.takeLast(MAX_UPLOADED_PATHS)) array.put(item)
        sp.edit().putString(KEY_UPLOADED, array.toString()).apply()
    }

    fun uploadedPaths(): Set<String> {
        val raw = sp.getString(KEY_UPLOADED, null) ?: return emptySet()
        val array = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return emptySet()
        return (0 until array.length()).mapNotNull { array.optString(it).ifBlank { null } }.toSet()
    }

    /**
     * "Band fayllar" ro'yxatini tozalaydi.
     *
     * "Qayta yuborish" bosilganda chaqiriladi. Fayl xato bilan band deb
     * belgilanib qolgan bo'lsa (masalan Odoo'dagi yozuv o'chirilgan) audio
     * boshqa hech qachon yuborilmasdi. Tozalash xavfsiz: fayl haqiqatan
     * boshqa qo'ng'iroqda bo'lsa server 409 qaytaradi va dublikat bo'lmaydi.
     */
    fun clearUploaded() = sp.edit().remove(KEY_UPLOADED).apply()

    /** Tugma bosilganda Odoo'dan "audiosi yetib bormaganlar" ro'yxati so'raladi. */
    var resendRequested: Boolean
        get() = sp.getBoolean(KEY_RESEND, false)
        set(value) = sp.edit().putBoolean(KEY_RESEND, value).apply()

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

    // --- Statistika (asosiy ekranda ko'rsatiladi) ---

    /** Bugun muvaffaqiyatli yuborilgan qo'ng'iroqlar soni. Kun almashsa nolga tushadi. */
    var sentToday: Int
        get() = if (sp.getString(KEY_SENT_DAY, "") == today()) sp.getInt(KEY_SENT_COUNT, 0) else 0
        private set(value) {
            sp.edit().putString(KEY_SENT_DAY, today()).putInt(KEY_SENT_COUNT, value).apply()
        }

    fun addSent(count: Int) {
        sentToday += count
    }

    /** Oxirgi muvaffaqiyatli aloqa vaqti (epoch millis, 0 = hali bo'lmagan). */
    var lastSyncMillis: Long
        get() = sp.getLong(KEY_LAST_SYNC, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_SYNC, value).apply()

    /** Oxirgi xato matni — bo'sh bo'lsa hammasi joyida. */
    var lastError: String
        get() = sp.getString(KEY_LAST_ERROR, "").orEmpty()
        set(value) = sp.edit().putString(KEY_LAST_ERROR, value).apply()

    private fun today(): String =
        android.text.format.DateFormat.format("yyyy-MM-dd", System.currentTimeMillis()).toString()

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
        const val KEY_SENT_DAY = "sent_day"
        const val KEY_SENT_COUNT = "sent_count"
        const val KEY_LAST_SYNC = "last_sync_millis"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_UPLOADED = "uploaded_paths"
        const val KEY_RESEND = "resend_requested"
        const val MAX_UPLOADED_PATHS = 300
        const val MAX_LOG_CHARS = 20_000
    }
}
