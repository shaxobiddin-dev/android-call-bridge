package uz.todoo.callbridge

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class OdooException(message: String, val invalidKey: Boolean = false) : Exception(message)

/** PIN tekshiruvi natijasi. */
sealed class PinResult {
    object Ok : PinResult()

    /** Xodim kartasida 4 xonali PIN yo'q yoki xodim kartasining o'zi yo'q. */
    object NotSet : PinResult()
    data class Wrong(val attemptsLeft: Int) : PinResult()
    data class Locked(val retryAfterSeconds: Int) : PinResult()

    /** API kalit noto'g'ri yoki muddati tugagan. */
    object InvalidKey : PinResult()
    object Offline : PinResult()

    /** Manzil noto'g'ri yoki Odoo modul yangilanmagan. */
    object ServerError : PinResult()
}

/** Audio yuklash natijasi. */
enum class UploadResult {
    /** Fayl biriktirildi (yoki allaqachon shu qo'ng'iroqda bor edi). */
    OK,

    /** Server aytdi: bu fayl BOSHQA qo'ng'iroqniki. Boshqa fayl qidirish kerak. */
    ALREADY_TAKEN,
}

/** Odoo'da bor, lekin audiosi yetib bormagan qo'ng'iroq. */
data class PendingCall(
    val callId: Long,
    val startMillis: Long,
    val endMillis: Long,
)

/**
 * Odoo `voip_android_bridge` moduli bilan aloqa.
 *
 * Autentifikatsiya — standart Odoo API key, `Authorization: Bearer <key>`.
 * So'rov kalit egasining huquqlari bilan bajariladi, ya'ni har xodim o'z
 * qo'ng'iroqlarini o'z nomidan yuboradi.
 */
class OdooApi(private val baseUrl: String, private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Odoo naive UTC beradi: "yyyy-MM-dd HH:mm:ss" */
    private val utcFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Qo'ng'iroqlarni yuboradi. Qayta yuborish xavfsiz — server dublikatni
     * o'zi ajratadi.
     *
     * @return device_call_id -> Odoo dagi voip.call id
     */
    fun push(deviceId: String, calls: List<CallEntry>, deviceName: String = ""): Map<String, Long> {
        val callsJson = JSONArray()
        for (call in calls) {
            callsJson.put(
                JSONObject()
                    .put("device_call_id", call.deviceCallId)
                    .put("phone_number", call.phoneNumber)
                    .put("direction", call.direction)
                    .put("state", call.state)
                    .put("start", call.startUtc)
                    .put("duration", call.durationSeconds)
            )
        }

        val params = JSONObject()
            .put("device_id", deviceId)
            .put("device_name", deviceName)
            .put("calls", callsJson)

        val result = jsonRpc("/voip/android/push", params)
        val ids = result.optJSONObject("call_ids") ?: return emptyMap()

        val map = mutableMapOf<String, Long>()
        val keys = ids.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = ids.getLong(key)
        }
        return map
    }

    /**
     * Odoo'dan audiosi yetib bormagan qo'ng'iroqlar ro'yxatini oladi.
     *
     * Ilovaning o'z navbatiga tayanish yetarli emas: ilova qayta o'rnatilgan,
     * xotirasi tozalangan yoki 24 soatlik urinish muddati tugagan bo'lishi
     * mumkin. Nimaning audiosi yo'qligini aniq faqat Odoo biladi.
     */
    fun fetchPending(deviceId: String, maxAgeDays: Int = 7): List<PendingCall> {
        val params = JSONObject()
            .put("device_id", deviceId)
            .put("max_age_days", maxAgeDays)

        val result = jsonRpc("/voip/android/pending", params)
        val calls = result.optJSONArray("calls") ?: return emptyList()

        val list = mutableListOf<PendingCall>()
        for (index in 0 until calls.length()) {
            val obj = calls.optJSONObject(index) ?: continue
            val id = obj.optLong("call_id")
            val start = parseUtc(obj.optString("start")) ?: continue
            val end = parseUtc(obj.optString("end")) ?: start
            if (id > 0) list += PendingCall(id, start, end)
        }
        return list
    }

    /** PIN'ni kalit egasining xodim kartasi bilan serverda solishtiradi. */
    fun verifyPin(pin: String): PinResult {
        return try {
            val result = jsonRpc("/voip/android/verify_pin", JSONObject().put("pin", pin))
            when {
                result.optBoolean("ok") -> PinResult.Ok
                result.optString("reason") == "not_set" -> PinResult.NotSet
                result.optString("reason") == "wrong" -> PinResult.Wrong(result.optInt("attempts_left"))
                result.optString("reason") == "locked" -> PinResult.Locked(result.optInt("retry_after"))
                else -> PinResult.ServerError
            }
        } catch (e: OdooException) {
            if (e.invalidKey) PinResult.InvalidKey else PinResult.ServerError
        } catch (e: IOException) {
            PinResult.Offline
        } catch (e: Exception) {
            // JSON o'rniga HTML kelgan yoki manzil URL sifatida yaroqsiz
            PinResult.ServerError
        }
    }

    /** Audio yozuvni mavjud qo'ng'iroqqa biriktiradi. */
    fun uploadRecording(callId: Long, file: File, mimeType: String): UploadResult {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("ufile", file.name, file.asRequestBody(mimeType.toMediaType()))
            .build()

        val request = Request.Builder()
            .url("$baseUrl/voip/android/recording/$callId")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            // 409 — server aytdi: ayni fayl boshqa qo'ng'iroqda bor.
            // Bu xato emas, bu "boshqa fayl qidir" degani.
            if (response.code == HTTP_CONFLICT) return UploadResult.ALREADY_TAKEN
            if (!response.isSuccessful) {
                throw OdooException(
                    "audio HTTP ${response.code}: ${response.body?.string()?.take(200)}"
                )
            }
            return UploadResult.OK
        }
    }

    private fun jsonRpc(path: String, params: JSONObject): JSONObject {
        val envelope = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "call")
            .put("params", params)

        val request = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(envelope.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw OdooException(
                    "HTTP ${response.code}: ${body.take(300)}",
                    invalidKey = response.code == HTTP_UNAUTHORIZED,
                )
            }
            val json = JSONObject(body)
            if (json.has("error")) {
                val error = json.getJSONObject("error")
                // jsonrpc route xatoni HTTP 200 ichida qaytaradi (odoo/http.py,
                // JsonRPCDispatcher.handle_error), shuning uchun nomidan ajratamiz
                val name = error.optJSONObject("data")?.optString("name").orEmpty()
                throw OdooException(
                    error.optString("message", "noma'lum xato"),
                    invalidKey = name.contains("Unauthorized"),
                )
            }
            return json.optJSONObject("result")
                ?: throw OdooException("javobda 'result' yo'q: ${body.take(300)}")
        }
    }

    private fun parseUtc(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { utcFormat.parse(value)?.time }.getOrNull()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val HTTP_CONFLICT = 409
        const val HTTP_UNAUTHORIZED = 401
    }
}
