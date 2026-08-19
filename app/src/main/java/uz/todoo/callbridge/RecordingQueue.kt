package uz.todoo.callbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Audio yuklash navbatidagi bitta element. */
data class PendingRecording(
    val odooCallId: Long,
    val startMillis: Long,
    val endMillis: Long,
    val attempts: Int,
)

/**
 * Audio yozuvlar navbati.
 *
 * Qo'ng'iroq jurnali yuborilgach `lastCallLogId` oldinga suriladi, shuning
 * uchun audio faylni keyin qidirish imkoni yo'qolmasligi kerak — bu navbat
 * aynan shu bo'shliqni yopadi. Fayl hali yozilib bitmagan bo'lsa keyingi
 * urinishda topiladi.
 */
class RecordingQueue(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("call_bridge_recordings", Context.MODE_PRIVATE)

    fun add(odooCallId: Long, startMillis: Long, endMillis: Long) {
        val items = read().toMutableList()
        if (items.any { it.odooCallId == odooCallId }) return
        items += PendingRecording(odooCallId, startMillis, endMillis, 0)
        write(items)
    }

    fun read(): List<PendingRecording> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            PendingRecording(
                odooCallId = obj.optLong("id"),
                startMillis = obj.optLong("start"),
                endMillis = obj.optLong("end"),
                attempts = obj.optInt("attempts"),
            )
        }
    }

    fun remove(odooCallId: Long) {
        write(read().filterNot { it.odooCallId == odooCallId })
    }

    /** Urinishlar sonini oshiradi; limitdan oshsa navbatdan chiqaradi. */
    fun markAttempt(odooCallId: Long) {
        val items = read().mapNotNull { item ->
            if (item.odooCallId != odooCallId) return@mapNotNull item
            val next = item.copy(attempts = item.attempts + 1)
            if (next.attempts >= MAX_ATTEMPTS) null else next
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
                    .put("attempts", item.attempts)
            )
        }
        sp.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "pending"

        /** Shundan keyin urinishni to'xtatamiz — audio yozuv umuman yo'q demak. */
        const val MAX_ATTEMPTS = 8
        const val MAX_ITEMS = 200
    }
}
