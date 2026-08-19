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
import java.util.concurrent.TimeUnit

class OdooException(message: String) : Exception(message)

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

    /**
     * Qo'ng'iroqlarni yuboradi. Qayta yuborish xavfsiz — server dublikatni
     * o'zi ajratadi.
     *
     * @return device_call_id -> Odoo dagi voip.call id
     */
    fun push(deviceId: String, calls: List<CallEntry>): Map<String, Long> {
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
            .put("calls", callsJson)

        val envelope = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "call")
            .put("params", params)

        val request = Request.Builder()
            .url("$baseUrl/voip/android/push")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(envelope.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw OdooException("HTTP ${response.code}: ${body.take(300)}")
            }
            val json = JSONObject(body)
            if (json.has("error")) {
                throw OdooException(json.getJSONObject("error").optString("message", "noma'lum xato"))
            }
            val result = json.optJSONObject("result")
                ?: throw OdooException("javobda 'result' yo'q: ${body.take(300)}")
            val ids = result.optJSONObject("call_ids") ?: return emptyMap()

            val map = mutableMapOf<String, Long>()
            val keys = ids.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = ids.getLong(key)
            }
            return map
        }
    }

    /** Audio yozuvni mavjud qo'ng'iroqqa biriktiradi. */
    fun uploadRecording(callId: Long, file: File, mimeType: String) {
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
            if (!response.isSuccessful) {
                throw OdooException("audio HTTP ${response.code}: ${response.body?.string()?.take(200)}")
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
