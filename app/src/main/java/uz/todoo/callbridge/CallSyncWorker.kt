package uz.todoo.callbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Qo'ng'iroq jurnalini o'qib Odoo'ga yuboradi, keyin audio yozuvni biriktiradi.
 *
 * To'liq avtomatik: foydalanuvchi hech narsa bosmaydi. Qo'ng'iroq tugagach
 * PhoneStateReceiver shu ishni navbatga qo'yadi. Audio fayl hali tayyor
 * bo'lmasa — o'zi qayta urinib turadi.
 */
class CallSyncWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = Prefs(applicationContext)

        if (!prefs.isConfigured) {
            return Result.success()
        }
        if (!hasCallLogPermission()) {
            prefs.log("Ruxsat yo'q: READ_CALL_LOG")
            return Result.success()
        }

        val api = OdooApi(prefs.odooUrl, prefs.apiKey)
        val reader = CallLogReader(applicationContext)
        val queue = RecordingQueue(applicationContext)

        // 1) Yangi qo'ng'iroqlar
        // Birinchi ishga tushish: butun tarixni to'kib yubormaymiz.
        // Boshlang'ich nuqtani jurnalning ENG OXIRIDAN belgilaymiz, shunda
        // faqat oxirgi bir nechta qo'ng'iroq yuboriladi.
        if (prefs.lastCallLogId == 0L) {
            val latest = reader.latestId()
            prefs.lastCallLogId = maxOf(0L, latest - FIRST_RUN_LIMIT)
            prefs.log("Birinchi ishga tushish — boshlang'ich jurnal ID: ${prefs.lastCallLogId}")
        }

        // Ikkinchi himoya: qanday xato bo'lsa ham eski tarix yuborilmasin
        val oldestAllowed = System.currentTimeMillis() - MAX_AGE_MS
        val all = reader.readNewerThan(prefs.lastCallLogId)
        val entries = all.filter { it.startMillis >= oldestAllowed }
        if (all.size != entries.size) {
            prefs.log("${all.size - entries.size} ta eski qo'ng'iroq o'tkazib yuborildi")
        }

        if (entries.isNotEmpty()) {
            val ids = try {
                api.push(prefs.deviceId, entries, prefs.deviceName)
            } catch (e: Exception) {
                prefs.lastError = e.message.orEmpty()
                prefs.log("Yuborilmadi (qayta urinamiz): ${e.message}")
                return Result.retry()
            }
            prefs.lastError = ""
            prefs.lastSyncMillis = System.currentTimeMillis()
            prefs.addSent(entries.size)
            prefs.log("Yuborildi: ${entries.size} ta qo'ng'iroq")

            // Audio yuklashni navbatga qo'yamiz — fayl hali yozilayotgan bo'lishi mumkin
            for (entry in entries) {
                val odooId = ids[entry.deviceCallId] ?: continue
                if (entry.durationSeconds <= 0) continue // javobsiz — yozuv bo'lmaydi
                queue.add(odooId, entry.startMillis, entry.endMillis)
            }
        }

        // Kursorni O'QILGANLARNING hammasi bo'yicha suramiz, yuborilganlar
        // bo'yicha emas — aks holda filtrlangan eski yozuvlar har safar
        // qayta o'qilib turadi
        if (all.isNotEmpty()) {
            prefs.lastCallLogId = all.maxOf { it.deviceCallId.toLong() }
        }

        // 2) Audio yozuvlar
        uploadRecordings(prefs, queue, api)

        // 3) Navbatda qolgan bo'lsa — o'zini qayta rejalashtiradi.
        //    Foydalanuvchi hech narsa bosmaydi.
        val remaining = queue.read()
        if (remaining.isNotEmpty()) {
            val attempts = remaining.minOf { it.attempts }
            val delayMinutes = when {
                attempts <= 1 -> 1L
                attempts <= 3 -> 3L
                else -> 10L
            }
            scheduleRecordingRetry(applicationContext, delayMinutes)
            prefs.log("Audio kutilmoqda (${remaining.size} ta) — $delayMinutes daq dan keyin qayta")
        }

        return Result.success()
    }

    private fun uploadRecordings(prefs: Prefs, queue: RecordingQueue, api: OdooApi) {
        val finder = RecordingFinder(applicationContext)
        for (pending in queue.read()) {
            val file = finder.find(pending.startMillis, pending.endMillis)
            if (file == null) {
                // Birinchi urinishda tashxis yozamiz — telefonda yozuv papkasi
                // bormi yoki umuman yozmayaptimi, shundan bilinadi
                if (pending.attempts == 0) {
                    prefs.log(finder.describeCandidates())
                }
                queue.markAttempt(pending.odooCallId)
                continue
            }
            try {
                api.uploadRecording(pending.odooCallId, file, finder.mimeTypeFor(file))
                queue.remove(pending.odooCallId)
                prefs.lastSyncMillis = System.currentTimeMillis()
                prefs.log("Audio yuklandi: ${file.name}")
            } catch (e: Exception) {
                queue.markAttempt(pending.odooCallId)
                prefs.log("Audio yuklanmadi: ${e.message}")
            }
        }
    }

    private fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val ONE_TIME = "call_sync_once"
        private const val RETRY = "call_sync_recording_retry"
        private const val PERIODIC = "call_sync_periodic"

        /** Birinchi ishga tushishda yuboriladigan eng oxirgi qo'ng'iroqlar soni. */
        private const val FIRST_RUN_LIMIT = 3

        /** Bundan eski qo'ng'iroq hech qachon yuborilmaydi (7 kun). */
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * Qo'ng'iroqdan keyin bir marta ishga tushirish.
         *
         * Kechikish kerak: qo'ng'iroq tugagan zahoti CallLog yozuvi va audio
         * fayl hali tayyor bo'lmaydi.
         */
        fun runAfterCall(context: Context, delaySeconds: Long = 15) {
            val request = OneTimeWorkRequestBuilder<CallSyncWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Sozlamalar saqlanganda yoki foydalanuvchi qo'lda so'raganda. */
        fun runNow(context: Context) = runAfterCall(context, delaySeconds = 0)

        /**
         * Audio hali topilmadi — keyinroq qayta urinish.
         *
         * Alohida nom ishlatiladi, aks holda yangi qo'ng'iroq bu ishni
         * bekor qilib yuborardi.
         */
        fun scheduleRecordingRetry(context: Context, delayMinutes: Long) {
            val request = OneTimeWorkRequestBuilder<CallSyncWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(RETRY, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Zaxira: broadcast o'tkazib yuborilgan bo'lsa ham har 15 daqiqada
         * tekshiriladi. Telefon qayta yoqilganda ham shu tiklanadi.
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<CallSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        private fun networkConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
