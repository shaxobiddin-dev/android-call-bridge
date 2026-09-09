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
import kotlin.math.abs

/** Bitta qo'ng'iroq va unga mos kelishi mumkin bo'lgan fayl. */
private data class Match(
    val call: PendingRecording,
    val candidate: RecordingCandidate,
    /** Fayl vaqti qo'ng'iroq oxiridan qancha uzoq — kichigi to'g'rirog'i. */
    val distance: Long,
)

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
        val all = reader.readNewerThan(prefs.lastCallLogId, READ_LIMIT)
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

            // Audio yuklashni navbatga qo'yamiz — fayl hali yozilayotgan
            // bo'lishi mumkin. Javobsiz/rad etilgan/ulanmagan qo'ng'iroqda
            // telefon umuman yozmaydi, ular navbatga tushmaydi.
            for (entry in entries) {
                val odooId = ids[entry.deviceCallId] ?: continue
                if (!entry.expectsRecording) continue
                queue.add(odooId, entry.startMillis, entry.endMillis)
            }
        }

        // Kursorni O'QILGANLARNING hammasi bo'yicha suramiz, yuborilganlar
        // bo'yicha emas — aks holda filtrlangan eski yozuvlar har safar
        // qayta o'qilib turadi
        if (all.isNotEmpty()) {
            prefs.lastCallLogId = all.maxOf { it.deviceCallId.toLong() }
        }
        // Bir yurishda hammasi sig'masa — davomi bor
        val moreToRead = all.size >= READ_LIMIT

        // 2) "Qayta yuborish" bosilgan bo'lsa: audiosi yetib bormaganlarni
        //    Odoo'ning O'ZIDAN so'raymiz. Ilova qayta o'rnatilgan yoki 24
        //    soatlik muddat tugagan bo'lsa ham shu yerda tiklanadi.
        if (prefs.resendRequested) {
            try {
                val pending = api.fetchPending(prefs.deviceId, RESEND_AGE_DAYS)
                for (call in pending) {
                    queue.restart(call.callId, call.startMillis, call.endMillis)
                }
                prefs.resendRequested = false
                prefs.log("Odoo'da audiosiz ${pending.size} ta qo'ng'iroq — qayta urinamiz")
            } catch (e: Exception) {
                // Bayroq saqlanadi, keyingi yurishda qayta so'raladi
                prefs.log("Ro'yxat olinmadi (qayta urinamiz): ${e.message}")
            }
        }

        // 3) Audio yozuvlar
        uploadRecordings(prefs, queue, api)

        // 4) Ish qolgan bo'lsa — o'zini qayta rejalashtiradi.
        //    Foydalanuvchi hech narsa bosmaydi.
        val remaining = queue.read()
        when {
            moreToRead -> {
                scheduleFollowUp(applicationContext, 1L)
                prefs.log("Jurnalda davomi bor — 1 daq dan keyin davom etamiz")
            }
            remaining.isNotEmpty() -> {
                val attempts = remaining.minOf { it.attempts }
                val delayMinutes = when {
                    attempts <= 1 -> 1L
                    attempts <= 3 -> 3L
                    attempts <= 10 -> 10L
                    else -> 30L
                }
                scheduleFollowUp(applicationContext, delayMinutes)
                prefs.log("Audio kutilmoqda (${remaining.size} ta) — $delayMinutes daq dan keyin qayta")
            }
        }

        return Result.success()
    }

    /**
     * Navbatdagi qo'ng'iroqlarga audio fayllarni taqsimlaydi.
     *
     * Har bir qo'ng'iroqni alohida "oynadagi eng yangi fayl" bilan bog'lash
     * NOTO'G'RI: bir daqiqa oraliqda qilingan ikki qo'ng'iroqning oynasi
     * kesishadi va birinchisi ikkinchisining faylini olib qo'yadi — Odoo'da
     * ikkita yozuvda bitta audio paydo bo'ladi.
     *
     * Shuning uchun hammasi BIRGA ko'riladi: barcha mumkin bo'lgan
     * (qo'ng'iroq, fayl) juftliklari masofa bo'yicha saralanadi va eng
     * yaqinidan boshlab taqsimlanadi. Bitta fayl bitta qo'ng'iroqqa, bitta
     * qo'ng'iroqqa bitta fayl.
     */
    private fun uploadRecordings(prefs: Prefs, queue: RecordingQueue, api: OdooApi) {
        val pending = queue.read()
        if (pending.isEmpty()) return

        val finder = RecordingFinder(applicationContext)
        val windowFrom = pending.minOf { it.startMillis }
        val windowTo = pending.maxOf { it.endMillis } + RecordingFinder.TOLERANCE_AFTER_MS
        val candidates = finder.collectCandidates(windowFrom, windowTo)

        val matches = mutableListOf<Match>()
        for (call in pending) {
            for (candidate in candidates) {
                // Yozuv qo'ng'iroq boshlanishidan oldin tugay olmaydi
                if (candidate.modified < call.startMillis) continue
                if (candidate.modified > call.endMillis + RecordingFinder.TOLERANCE_AFTER_MS) continue
                matches += Match(call, candidate, abs(candidate.modified - call.endMillis))
            }
        }
        matches.sortBy { it.distance }

        val takenCalls = mutableSetOf<Long>()
        val takenFiles = mutableSetOf<String>()
        val assigned = mutableListOf<Match>()
        for (match in matches) {
            if (match.call.odooCallId in takenCalls) continue
            if (match.candidate.path in takenFiles) continue
            takenCalls += match.call.odooCallId
            takenFiles += match.candidate.path
            assigned += match
        }

        for (match in assigned) {
            val file = match.candidate.file
            try {
                val result = api.uploadRecording(
                    match.call.odooCallId, file, finder.mimeTypeFor(file)
                )
                when (result) {
                    UploadResult.OK -> {
                        prefs.markUploaded(match.candidate.path)
                        queue.remove(match.call.odooCallId)
                        prefs.lastSyncMillis = System.currentTimeMillis()
                        prefs.log("Audio yuklandi: ${file.name}")
                    }
                    UploadResult.ALREADY_TAKEN -> {
                        // Server aytdi: bu fayl boshqa qo'ng'iroqniki. Faylni
                        // band deb belgilaymiz, qo'ng'iroq navbatda qoladi va
                        // keyingi safar boshqa nomzod ko'riladi.
                        prefs.markUploaded(match.candidate.path)
                        queue.markAttempt(match.call.odooCallId)
                        prefs.log("Fayl boshqa qo'ng'iroqniki: ${file.name}")
                    }
                }
            } catch (e: Exception) {
                queue.markAttempt(match.call.odooCallId)
                prefs.log("Audio yuklanmadi: ${e.message}")
            }
        }

        // Fayl topilmaganlar — urinishni belgilaymiz (24 soatdan oshsa chiqadi)
        val assignedIds = assigned.map { it.call.odooCallId }.toSet()
        val unmatched = pending.filterNot { it.odooCallId in assignedIds }
        if (unmatched.any { it.attempts == 0 }) {
            // Telefonda yozuv papkasi bormi — kompyutersiz shundan bilinadi
            prefs.log(finder.describeCandidates())
        }
        for (call in unmatched) {
            queue.markAttempt(call.odooCallId)
        }
    }

    private fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val ONE_TIME = "call_sync_once"
        private const val FOLLOW_UP = "call_sync_follow_up"
        private const val PERIODIC = "call_sync_periodic"

        /** Birinchi ishga tushishda yuboriladigan eng oxirgi qo'ng'iroqlar soni. */
        const val FIRST_RUN_LIMIT = 3

        /** Bundan eski qo'ng'iroq hech qachon yuborilmaydi (7 kun). */
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        /** Bir yurishda jurnaldan o'qiladigan eng ko'p yozuv. */
        const val READ_LIMIT = 200

        /** "Qayta yuborish" necha kunlik orqaga qaraydi. */
        const val RESEND_AGE_DAYS = 7

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
         * Ish tugamadi — keyinroq davom ettirish (audio hali topilmadi yoki
         * jurnalda o'qilmagan yozuv qoldi).
         *
         * Alohida nom ishlatiladi, aks holda yangi qo'ng'iroq bu ishni
         * bekor qilib yuborardi.
         */
        fun scheduleFollowUp(context: Context, delayMinutes: Long) {
            val request = OneTimeWorkRequestBuilder<CallSyncWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(FOLLOW_UP, ExistingWorkPolicy.REPLACE, request)
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
