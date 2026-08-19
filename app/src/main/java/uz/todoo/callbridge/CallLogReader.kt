package uz.todoo.callbridge

import android.content.Context
import android.provider.CallLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Bitta qo'ng'iroq — Odoo'ga yuboriladigan ko'rinishda. */
data class CallEntry(
    val deviceCallId: String,
    val phoneNumber: String,
    val direction: String,
    val state: String,
    /** Odoo naive UTC kutadi: "yyyy-MM-dd HH:mm:ss" */
    val startUtc: String,
    val durationSeconds: Int,
    /** Audio faylni qidirish uchun — lokal epoch millis */
    val startMillis: Long,
) {
    val endMillis: Long get() = startMillis + durationSeconds * 1000L
}

/**
 * Android tizim qo'ng'iroq jurnalini o'qiydi.
 *
 * Android HAR BIR qo'ng'iroqni CallLog ga yozadi — qaysi ilovadan qilinganidan
 * qat'i nazar. Shuning uchun telefonning o'z dialer'idan qilingan qo'ng'iroq
 * ham shu yerda bo'ladi.
 */
class CallLogReader(private val context: Context) {

    private val utcFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** [afterId] dan keyingi barcha qo'ng'iroqlarni qaytaradi (eskisidan yangisiga). */
    fun readNewerThan(afterId: Long, limit: Int = 200): List<CallEntry> {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )
        val result = mutableListOf<CallEntry>()

        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls._ID} > ?",
            arrayOf(afterId.toString()),
            "${CallLog.Calls._ID} ASC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
            val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)

            while (cursor.moveToNext() && result.size < limit) {
                val id = cursor.getLong(idIdx)
                val number = cursor.getString(numberIdx).orEmpty()
                val type = cursor.getInt(typeIdx)
                val date = cursor.getLong(dateIdx)
                val duration = cursor.getInt(durationIdx)

                val (direction, state) = mapType(type)
                result += CallEntry(
                    deviceCallId = id.toString(),
                    phoneNumber = number,
                    direction = direction,
                    state = state,
                    startUtc = utcFormat.format(Date(date)),
                    durationSeconds = duration,
                    startMillis = date,
                )
            }
        }
        return result
    }

    /** Jurnaldagi eng katta ID — birinchi ishga tushirishda "eskilarni yubormaslik" uchun. */
    fun latestId(): Long {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID),
            null,
            null,
            "${CallLog.Calls._ID} DESC",
        )?.use { cursor ->
            // LIMIT sortOrder ichida ba'zi Android versiyalarida ishlamaydi —
            // shuning uchun birinchi qatorni olamiz
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return 0L
    }

    /** CallLog turi -> voip.call dagi (direction, state). */
    private fun mapType(type: Int): Pair<String, String> = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming" to "terminated"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing" to "terminated"
        CallLog.Calls.MISSED_TYPE -> "incoming" to "missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "incoming" to "terminated"
        CallLog.Calls.REJECTED_TYPE -> "incoming" to "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "incoming" to "rejected"
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "incoming" to "terminated"
        else -> "outgoing" to "terminated"
    }
}
