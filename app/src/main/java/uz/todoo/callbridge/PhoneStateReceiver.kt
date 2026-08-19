package uz.todoo.callbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * Qo'ng'iroq tugashini ushlaydi.
 *
 * IDLE holatiga o'tish = qo'ng'iroq tugadi (javob berilgan, javobsiz yoki rad
 * etilgan — farqi yo'q, CallLog hammasini yozadi). Shundan keyin sinxronizatsiya
 * ishga tushadi.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        if (state != TelephonyManager.EXTRA_STATE_IDLE) return

        // CallLog yozuvi va audio fayl darhol tayyor bo'lmaydi — kutamiz
        CallSyncWorker.runAfterCall(context)
    }
}
