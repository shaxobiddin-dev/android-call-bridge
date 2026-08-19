package uz.todoo.callbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
 * WorkManager ishlatilgani uchun internet yo'q bo'lsa ish kutib turadi va
 * tarmoq paydo bo'lganda o'zi bajariladi — hech narsa yo'qolmaydi.
 */
class CallSyncWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = Prefs(applicationContext)

        if (!prefs.isConfigured) {
            prefs.log("Sozlanmagan: Odoo URL yoki API key yo'q")
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
        val entries = reader.readNewerThan(prefs.lastCallLogId)
        if (entries.isNotEmpty()) {
            val ids = try {
                api.push(prefs.deviceId, entries)
            } catch (e: Exception) {
                prefs.log("Yuborilmadi (qayta urinamiz): ${e.message}")
                return Result.retry()
            }

            prefs.lastCallLogId = entries.maxOf { it.deviceCallId.toLong() }
            prefs.log("Yuborildi: ${entries.size} ta qo'ng'iroq")

            // Audio yuklashni navbatga qo'yamiz (fayl hali yozilayotgan bo'lishi mumkin)
            for (entry in entries) {
                val odooId = ids[entry.deviceCallId] ?: continue
                if (entry.durationSeconds <= 0) continue // javobsiz — yozuv bo'lmaydi
                queue.add(odooId, entry.startMillis, entry.endMillis)
            }
        }

        // 2) Audio yozuvlar
        uploadRecordings(prefs, queue, api)

        return Result.success()
    }

    private fun uploadRecordings(prefs: Prefs, queue: RecordingQueue, api: OdooApi) {
        val finder = RecordingFinder(applicationContext)
        for (pending in queue.read()) {
            val file = finder.find(pending.startMillis, pending.endMillis)
            if (file == null) {
                queue.markAttempt(pending.odooCallId)
                continue
            }
            try {
                api.uploadRecording(pending.odooCallId, file, finder.mimeTypeFor(file))
                queue.remove(pending.odooCallId)
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
        private const val PERIODIC = "call_sync_periodic"

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
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Foydalanuvchi "Hozir yuborish" bosganda. */
        fun runNow(context: Context) = runAfterCall(context, delaySeconds = 0)

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
