package uz.todoo.callbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Telefon o'chib-yonganda yoki ilova yangilanganda davriy ishni tiklaydi.
 * Foydalanuvchi hech narsa qilmaydi.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                CallSyncWorker.schedulePeriodic(context)
                CallSyncWorker.runAfterCall(context, delaySeconds = 60)
                Prefs(context).log("Ishga tushdi: ${intent.action}")
            }
        }
    }
}
