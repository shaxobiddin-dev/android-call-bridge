package uz.todoo.callbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Audio yuklash navbatidagi bitta element. */
data class PendingRecording(
    val odooCallId: Long,
    val startMillis: Long,
    val endMillis: Long,
    /** Navbatga birinchi marta qo'yilgan payt — 24 soatlik muddat shundan sanaladi. */
    val firstSeenMillis: Long,
    val attempts: Int,
)

/**
 * Audio yozuvlar navbati.
 *
 * Qo'ng'iroq jurnali yuborilgach `lastCallLogId` oldinga suriladi, shuning
 * uchun audio faylni keyin qidirish imkoni yo'qolmasligi kerak — bu navbat
 * aynan shu bo'shliqni yopadi. Fayl hali yozilib bitmagan bo'lsa keyingi
 * urinishda topiladi.
 *
 * Navbat 24 soat urinadi. Shundan keyin element tashlab yuboriladi: telefon
 * yozuvni umuman yozmagan bo'lsa (masalan dialer sozlamasi o'chirilgan)
 * cheksiz urinish batareyani yeydi. Foydalanuvchi "Qayta yuborish" tugmasini
 * bossa, Odoo'ning o'zidan ro'yxat qayta so'raladi va urinish yangidan
 * boshlanadi.
 */
class RecordingQueue(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("call_bridge_recordings", Context.MODE_PRIVATE)

    /** Yangi element qo'shadi. Allaqachon navbatda bo'lsa tegmaydi. */
    fun add(odooCallId: Long, startMillis: Long, endMillis: Long) {
        val items = read().toMutableList()
        if (items.any { it.odooCallId == odooCallId }) return
        items += PendingRecording(
            odooCallId = odooCallId,
            startMillis = startMillis,
            endMillis = endMillis,
            firstSeenMillis = System.currentTimeMillis(),
            attempts = 0,
        )
        write(items)
    }

    /**
     * Odoo aytgan elementni navbatga qaytaradi va muddatni noldan boshlaydi.
     * "Qayta yuborish" tugmasi uchun — 24 soat o'tib tashlangan qo'ng'iroq
     * ham shu yo'l bilan yana urinishga tushadi.
     */
    fun restart(odooCallId: Long, startMillis: Long, endMillis: Long) {
        val items = read().filterNot { it.odooCallId == odooCallId }.toMutableList()
        items += PendingRecording(
            odooCallId = odooCallId,
            startMillis = startMillis,
            endMillis = endMillis,
            firstSeenMillis = System.currentTimeMillis(),
            attempts = 0,
        )
        write(items)
    }

    fun read(): List<PendingRecording> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val now = System.currentTimeMillis()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optLong("id")
            if (id <= 0L) return@mapNotNull null
            // Eski versiyadan qolgan yozuvda "first" bo'lmaydi — hozirdan sanaymiz
            val firstSeen = obj.optLong("first", 0L).takeIf { it > 0L } ?: now
            PendingRecording(
                odooCallId = id,
                startMillis = obj.optLong("start"),
                endMillis = obj.optLong("end"),
                firstSeenMillis = firstSeen,
                attempts = obj.optInt("attempts"),
            )
        }
    }

    fun remove(odooCallId: Long) {
        write(read().filterNot { it.odooCallId == odooCallId })
    }

    /** Urinishlar sonini oshiradi; 24 soatdan oshgan element navbatdan chiqadi. */
    fun markAttempt(odooCallId: Long) {
        val now = System.currentTimeMillis()
        val items = read().mapNotNull { item ->
            if (item.odooCallId != odooCallId) return@mapNotNull item
            val next = item.copy(attempts = item.attempts + 1)
            if (now - next.firstSeenMillis >= MAX_AGE_MS) null else next
        }
        write(items)
    }

    private fun write(items: List<PendingRecording>) {
        val array = JSONArray()
        for (item in items.takeLast(MAX_ITEMS)) {
            array.put(
                JSONObject()
                    .put("id", item.odooCallId)
                    .put("start", item.startMillis)
                    .put("end", item.endMillis)
                    .put("first", item.firstSeenMillis)
                    .put("attempts", item.attempts)
            )
        }
        sp.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "pending"

        /** Shu muddatdan keyin avtomatik urinish to'xtaydi (24 soat). */
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        const val MAX_ITEMS = 500
    }
}
