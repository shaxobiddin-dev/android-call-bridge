package uz.todoo.callbridge

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Nomzod audio fayl va uning oxirgi o'zgarish vaqti. */
data class RecordingCandidate(val file: File, val modified: Long) {
    val path: String get() = file.absolutePath
}

/**
 * Qo'ng'iroq audio yozuvini topadi.
 *
 * MUHIM: Android 10 (2019) dan boshlab uchinchi tomon ilovasi qo'ng'iroq
 * audiosini O'ZI yoza olmaydi (VOICE_CALL manbasi root'siz yopiq). Shuning
 * uchun bu klass faqat telefonning O'Z dialer'i yozgan faylni QIDIRADI.
 *
 * Brend har xil papkaga yozadi, shuning uchun papkani qo'lda sozlamaymiz:
 * birinchi muvaffaqiyatli topilgan papka eslab qolinadi va keyingi safar
 * birinchi bo'lib o'sha ko'riladi.
 *
 * Bu klass QAROR QABUL QILMAYDI — faqat oynadagi barcha nomzodlarni
 * qaytaradi. Qaysi fayl qaysi qo'ng'iroqniki ekanini `CallSyncWorker`
 * hammasini birga ko'rib hal qiladi: ketma-ket ikki qo'ng'iroqning oynasi
 * kesishadi va har birini alohida "eng yangi fayl" bilan bog'lash
 * oldingi versiyada aynan dublikatga olib kelgan edi.
 */
class RecordingFinder(private val context: Context) {

    private val prefs = Prefs(context)

    /**
     * [fromMillis] va [toMillis] oralig'ida o'zgargan barcha audio fayllar.
     * Allaqachon Odoo'ga yuklangan fayllar ro'yxatga tushmaydi.
     */
    fun collectCandidates(fromMillis: Long, toMillis: Long): List<RecordingCandidate> {
        val found = LinkedHashMap<String, RecordingCandidate>()

        // 1) Eslab qolingan papka — eng tez yo'l
        prefs.recordingDir?.let { remembered ->
            collectFromDir(File(remembered), fromMillis, toMillis, found)
        }

        // 2) Ma'lum papkalar ro'yxati
        val root = Environment.getExternalStorageDirectory()
        for (relative in KNOWN_DIRS) {
            val dir = File(root, relative)
            val before = found.size
            collectFromDir(dir, fromMillis, toMillis, found)
            if (found.size > before && prefs.recordingDir != dir.absolutePath) {
                prefs.recordingDir = dir.absolutePath
                prefs.log("Yozuv papkasi topildi: ${dir.absolutePath}")
            }
        }

        // 3) MediaStore (papka nomi noma'lum bo'lsa ham indekslangan bo'lishi mumkin)
        collectFromMediaStore(fromMillis, toMillis, found)

        // 4) To'liq skanerlash — brend ro'yxatda yo'q va MediaStore ham
        //    indekslamagan bo'lsa. Vaqt oynasi tor bo'lgani uchun begona
        //    fayl tushib qolishi amalda kutilmaydi.
        if (found.isEmpty()) {
            collectEverywhere(root, fromMillis, toMillis, found)
            found.values.firstOrNull()?.file?.parent?.let { parent ->
                prefs.recordingDir = parent
                prefs.log("Yozuv to'liq qidiruvda topildi: $parent")
            }
        }

        // Band bo'lgan fayllarni bir marta o'qib chiqaramiz
        val uploaded = prefs.uploadedPaths()
        return found.values.filterNot { uploaded.contains(it.path) }
    }

    /**
     * Butun ichki xotirani aylanib chiqadi.
     *
     * `Android/` papkasi o'tkazib yuboriladi: Android 11+ da unga
     * MANAGE_EXTERNAL_STORAGE bilan ham kirib bo'lmaydi, shuning uchun
     * u yerni tekshirish faqat vaqt yo'qotish.
     */
    private fun collectEverywhere(
        root: File,
        from: Long,
        to: Long,
        into: MutableMap<String, RecordingCandidate>,
    ) {
        root.walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .onEnter { dir -> dir.name != "Android" && !dir.name.startsWith(".") }
            .forEach { file -> consider(file, from, to, into) }
    }

    private fun collectFromDir(
        dir: File,
        from: Long,
        to: Long,
        into: MutableMap<String, RecordingCandidate>,
        depth: Int = 0,
    ) {
        if (depth > MAX_DIR_DEPTH) return
        if (!dir.isDirectory || !dir.canRead()) return
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                collectFromDir(file, from, to, into, depth + 1)
            } else {
                consider(file, from, to, into)
            }
        }
    }

    private fun consider(
        file: File,
        from: Long,
        to: Long,
        into: MutableMap<String, RecordingCandidate>,
    ) {
        if (!file.isFile) return
        if (!isAudio(file.name)) return
        if (file.length() < MIN_SIZE_BYTES) return
        val modified = file.lastModified()
        if (modified !in from..to) return
        val path = file.absolutePath
        if (!into.containsKey(path)) into[path] = RecordingCandidate(file, modified)
    }

    private fun collectFromMediaStore(
        from: Long,
        to: Long,
        into: MutableMap<String, RecordingCandidate>,
    ) {
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.SIZE,
        )
        // DATE_MODIFIED soniyada saqlanadi
        val selection = "${MediaStore.Audio.Media.DATE_MODIFIED} BETWEEN ? AND ?"
        val args = arrayOf((from / 1000).toString(), (to / 1000).toString())

        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.Audio.Media.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataIdx) ?: continue
                    if (cursor.getLong(sizeIdx) < MIN_SIZE_BYTES) continue
                    val file = File(path)
                    if (!file.exists() || !file.canRead() || !isAudio(file.name)) continue
                    consider(file, from, to, into)
                }
            }
        }
    }

    private fun isAudio(name: String): Boolean =
        AUDIO_EXTENSIONS.any { name.lowercase().endsWith(it) }

    fun mimeTypeFor(file: File): String = when {
        file.name.endsWith(".mp3", true) -> "audio/mpeg"
        file.name.endsWith(".m4a", true) -> "audio/mp4"
        file.name.endsWith(".aac", true) -> "audio/aac"
        file.name.endsWith(".amr", true) -> "audio/amr"
        file.name.endsWith(".wav", true) -> "audio/wav"
        file.name.endsWith(".ogg", true) -> "audio/ogg"
        file.name.endsWith(".3gp", true) -> "audio/3gpp"
        else -> "audio/mpeg"
    }

    /**
     * Tashxis: qurilmada qaysi yozuv papkalari umuman mavjudligini ko'rsatadi.
     * `adb` yo'q, shuning uchun bu ma'lumot ilova jurnaliga yoziladi.
     */
    fun describeCandidates(): String {
        val root = Environment.getExternalStorageDirectory()
        val found = KNOWN_DIRS.mapNotNull { relative ->
            val dir = File(root, relative)
            if (!dir.isDirectory) return@mapNotNull null
            val audioCount = dir.walkTopDown().maxDepth(2)
                .count { it.isFile && isAudio(it.name) }
            "$relative ($audioCount audio)"
        }
        return if (found.isEmpty()) {
            "Yozuv papkasi topilmadi. Telefon qo'ng'iroqni yozmayotgan bo'lishi mumkin."
        } else {
            "Mavjud papkalar: ${found.joinToString("; ")}"
        }
    }

    companion object {
        /**
         * Yozuv fayli qo'ng'iroq tugagandan keyin biroz kechikib yopiladi.
         *
         * Avval 5 daqiqa edi: bir necha daqiqa oraliqda qilingan ikki
         * qo'ng'iroqning oynasi kesishib, bitta fayl ikkalasiga ham
         * biriktirilardi. 2 daqiqa amalda yetarli.
         */
        const val TOLERANCE_AFTER_MS = 120_000L

        /** Juda kichik fayl — bu yozuv emas. */
        const val MIN_SIZE_BYTES = 2_000L

        /** To'liq skanerlash chuqurligi — xotirani cheksiz aylanmaslik uchun. */
        private const val MAX_SCAN_DEPTH = 6

        /** Ma'lum papka ichida nechta qavat pastga tushamiz. */
        private const val MAX_DIR_DEPTH = 3

        private val AUDIO_EXTENSIONS =
            listOf(".mp3", ".m4a", ".aac", ".amr", ".wav", ".ogg", ".3gp")

        /** Brendlarning odatiy qo'ng'iroq yozuvi papkalari. */
        private val KNOWN_DIRS = listOf(
            "MIUI/sound_recorder/call_rec",   // Xiaomi / Redmi / POCO
            "Recordings/Call",                 // Android 14+ standart, Pixel
            "Recordings",
            "Record/Call",                     // OnePlus / Oppo / Realme
            "Sounds/CallRecord",               // Samsung
            "Call",
            "CallRecordings",
            "PhoneRecord",
            "Music/Recordings",
            "Android/data/com.android.dialer/files",
        )
    }
}
