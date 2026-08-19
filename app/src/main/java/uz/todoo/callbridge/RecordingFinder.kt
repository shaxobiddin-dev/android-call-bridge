package uz.todoo.callbridge

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

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
 */
class RecordingFinder(private val context: Context) {

    private val prefs = Prefs(context)

    /**
     * [startMillis] va [endMillis] oralig'ida yaratilgan audio faylni qaytaradi.
     * Topilmasa null.
     */
    fun find(startMillis: Long, endMillis: Long): File? {
        // Yozuv fayli qo'ng'iroq tugagandan keyin ancha kechikib yopilishi mumkin
        val from = startMillis - TOLERANCE_BEFORE_MS
        val to = endMillis + TOLERANCE_AFTER_MS

        // 1) Eslab qolingan papka — eng tez yo'l
        prefs.recordingDir?.let { remembered ->
            scanDir(File(remembered), from, to)?.let { return it }
        }

        // 2) Ma'lum papkalar ro'yxati
        val root = Environment.getExternalStorageDirectory()
        for (relative in KNOWN_DIRS) {
            val dir = File(root, relative)
            scanDir(dir, from, to)?.let {
                prefs.recordingDir = dir.absolutePath
                prefs.log("Yozuv papkasi topildi: ${dir.absolutePath}")
                return it
            }
        }

        // 3) MediaStore (papka nomi noma'lum bo'lsa ham indekslangan bo'lishi mumkin)
        findInMediaStore(from, to)?.let { return it }

        // 4) To'liq skanerlash — brend ro'yxatda yo'q va MediaStore ham
        //    indekslamagan bo'lsa. Vaqt oynasi tor bo'lgani uchun begona
        //    fayl tushib qolishi amalda kutilmaydi.
        scanEverywhere(root, from, to)?.let { file ->
            file.parent?.let { prefs.recordingDir = it }
            prefs.log("Yozuv to'liq qidiruvda topildi: ${file.parent}")
            return file
        }

        return null
    }

    /**
     * Butun ichki xotirani aylanib chiqadi.
     *
     * `Android/` papkasi o'tkazib yuboriladi: Android 11+ da unga
     * MANAGE_EXTERNAL_STORAGE bilan ham kirib bo'lmaydi, shuning uchun
     * u yerni tekshirish faqat vaqt yo'qotish.
     */
    private fun scanEverywhere(root: File, from: Long, to: Long): File? {
        var best: File? = null
        root.walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .onEnter { dir -> dir.name != "Android" && !dir.name.startsWith(".") }
            .forEach { file ->
                if (!file.isFile) return@forEach
                if (!isAudio(file.name)) return@forEach
                if (file.length() < MIN_SIZE_BYTES) return@forEach
                if (file.lastModified() !in from..to) return@forEach
                if (best == null || file.lastModified() > best!!.lastModified()) best = file
            }
        return best
    }

    private fun scanDir(dir: File, from: Long, to: Long): File? {
        if (!dir.isDirectory || !dir.canRead()) return null
        val files = dir.listFiles() ?: return null
        var best: File? = null
        for (file in files) {
            if (file.isDirectory) {
                scanDir(file, from, to)?.let { nested ->
                    if (best == null || nested.lastModified() > best!!.lastModified()) best = nested
                }
                continue
            }
            if (!isAudio(file.name)) continue
            if (file.length() < MIN_SIZE_BYTES) continue
            if (file.lastModified() !in from..to) continue
            if (best == null || file.lastModified() > best!!.lastModified()) best = file
        }
        return best
    }

    private fun findInMediaStore(from: Long, to: Long): File? {
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.SIZE,
        )
        // DATE_MODIFIED soniyada saqlanadi
        val selection = "${MediaStore.Audio.Media.DATE_MODIFIED} BETWEEN ? AND ?"
        val args = arrayOf((from / 1000).toString(), (to / 1000).toString())

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
                if (file.exists() && file.canRead() && isAudio(file.name)) {
                    prefs.recordingDir = file.parent
                    prefs.log("Yozuv MediaStore'dan topildi: ${file.parent}")
                    return file
                }
            }
        }
        return null
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

    private companion object {
        /** Yozuv fayli qo'ng'iroq boshlanishidan sal oldin yaratilishi mumkin. */
        const val TOLERANCE_BEFORE_MS = 60_000L

        /** Uzoq qo'ng'iroqda fayl tugagandan keyin ancha kech yopiladi. */
        const val TOLERANCE_AFTER_MS = 300_000L

        /** Juda kichik fayl — bu yozuv emas. */
        const val MIN_SIZE_BYTES = 2_000L

        /** To'liq skanerlash chuqurligi — xotirani cheksiz aylanmaslik uchun. */
        const val MAX_SCAN_DEPTH = 6

        val AUDIO_EXTENSIONS = listOf(".mp3", ".m4a", ".aac", ".amr", ".wav", ".ogg", ".3gp")

        /** Brendlarning odatiy qo'ng'iroq yozuvi papkalari. */
        val KNOWN_DIRS = listOf(
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
