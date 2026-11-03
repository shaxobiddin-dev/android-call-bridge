package uz.todoo.callbridge

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.UUID

/** Sozlamalar va sinxronizatsiya holati (SharedPreferences). */
class Prefs(context: Context) {

    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences("call_bridge", Context.MODE_PRIVATE)

    init {
        // Eski versiyalar jurnalni shu yerda saqlardi (xom server javoblari bilan)
        if (sp.contains(KEY_LOG)) sp.edit().remove(KEY_LOG).apply()
    }

    /**
     * Getter ham tozalaydi: ilova yangilanganda eski saqlangan manzil qayta
     * "Saqlash" bosilmasa ham tuzaladi, fondagi worker ham shu qiymatni oladi.
     */
    var odooUrl: String
        get() = normalizeUrl(sp.getString(KEY_URL, "").orEmpty())
        set(value) = sp.edit().putString(KEY_URL, normalizeUrl(value)).apply()

    var apiKey: String
        get() = sp.getString(KEY_API, "").orEmpty()
        set(value) = sp.edit().putString(KEY_API, value.trim()).apply()

    /** Odoo'da guruhlash uchun ko'rinadigan nom, masalan "Ozodbek — Samsung S23+". */
    var deviceName: String
        get() = sp.getString(KEY_DEVICE_NAME, "").orEmpty()
        set(value) = sp.edit().putString(KEY_DEVICE_NAME, value.trim()).apply()

    /** Shu manzil va kalit bilan PIN kamida bir marta serverda tasdiqlanganmi. */
    var pinVerified: Boolean
        get() = sp.getBoolean(KEY_PIN_VERIFIED, false)
        set(value) = sp.edit().putBoolean(KEY_PIN_VERIFIED, value).apply()

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

    /** Texnik yozuv faqat logcat'ga tushadi, xodimga hech qayerda ko'rinmaydi. */
    fun log(line: String) {
        Log.i(LOG_TAG, line)
    }

    companion object {
        private const val KEY_URL = "odoo_url"
        private const val KEY_API = "api_key"
        private const val KEY_DEVICE_NAME = "device_name_label"
        private const val KEY_LAST_ID = "last_call_log_id"
        private const val KEY_REC_DIR = "recording_dir"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_LOG = "status_log"
        private const val KEY_SENT_DAY = "sent_day"
        private const val KEY_SENT_COUNT = "sent_count"
        private const val KEY_LAST_SYNC = "last_sync_millis"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_UPLOADED = "uploaded_paths"
        private const val KEY_RESEND = "resend_requested"
        private const val KEY_PIN_VERIFIED = "pin_verified"
        private const val MAX_UPLOADED_PATHS = 300
        private const val LOG_TAG = "OdooCalls"

        /** Odoo web client yo'llari (web/controllers/home.py:46). */
        private val WEB_CLIENT_SEGMENTS = setOf("odoo", "web", "scoped_app")

        /**
         * Brauzerdan nusxalangan manzilni server manziliga keltiradi.
         *
         * `https://x.com/odoo/...` saqlansa so'rov `/odoo/<path>` web client
         * route'iga tushadi va "invalid CSRF token" qaytaradi.
         */
        fun normalizeUrl(raw: String): String {
            val cleaned = raw.trim().substringBefore('#').substringBefore('?').trimEnd('/')
            val schemeEnd = cleaned.indexOf("://")
            if (schemeEnd < 0) return cleaned
            val pathStart = cleaned.indexOf('/', schemeEnd + 3)
            if (pathStart < 0) return cleaned
            val origin = cleaned.substring(0, pathStart)
            val kept = cleaned.substring(pathStart + 1).split('/')
                .takeWhile { it.lowercase() !in WEB_CLIENT_SEGMENTS }
            return (listOf(origin) + kept).joinToString("/")
        }
    }
}
