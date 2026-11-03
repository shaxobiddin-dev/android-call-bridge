package uz.todoo.callbridge

import android.os.SystemClock

/**
 * Ilova ochiq sessiyasi. Faqat xotirada turadi: jarayon o'lsa PIN qayta so'raladi.
 *
 * Vaqt elapsedRealtime bilan o'lchanadi, telefon soatini o'zgartirib bo'lmaydi.
 */
object PinSession {

    /** Ruxsat berish uchun sozlamalarga o'tilganda PIN qayta so'ralmasin. */
    private const val BACKGROUND_GRACE_MS = 5 * 60 * 1000L

    private var unlocked = false
    private var backgroundedAt = 0L
    private var formResetMessage: Int? = null

    fun isUnlocked(): Boolean {
        if (
            unlocked && backgroundedAt != 0L &&
            SystemClock.elapsedRealtime() - backgroundedAt > BACKGROUND_GRACE_MS
        ) {
            unlocked = false
        }
        return unlocked
    }

    fun unlock() {
        unlocked = true
        backgroundedAt = 0L
    }

    fun lock() {
        unlocked = false
    }

    fun onForeground() {
        backgroundedAt = 0L
    }

    fun onBackground() {
        if (unlocked) backgroundedAt = SystemClock.elapsedRealtime()
    }

    /** PIN ekrani kalitni o'chirgach, asosiy ekran formani shu xabar bilan yangilaydi. */
    fun requestFormReset(message: Int) {
        formResetMessage = message
    }

    fun takeFormResetMessage(): Int? = formResetMessage.also { formResetMessage = null }
}
