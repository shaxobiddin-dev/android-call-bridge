package uz.todoo.callbridge

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import uz.todoo.callbridge.databinding.ActivityPinBinding

/**
 * Ilovaga kirish. PIN telefonda saqlanmaydi, har safar Odoo tekshiradi
 * (`/voip/android/verify_pin`). Fondagi yuborish bu ekranga bog'liq emas.
 */
class PinActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPinBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPinBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        // Orqaga bosilsa asosiy ekran ochilib ketmasin
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finishAffinity()
                }
            }
        )

        binding.inputPin.doAfterTextChanged { text ->
            val complete = text?.length == PIN_LENGTH
            binding.btnPin.isEnabled = complete
            if (complete) submit()
        }
        binding.btnPin.setOnClickListener { submit() }
        binding.btnReconfigure.setOnClickListener { confirmReconfigure() }
    }

    private fun submit() {
        val pin = binding.inputPin.text?.toString().orEmpty()
        if (pin.length != PIN_LENGTH || !binding.inputPin.isEnabled) return

        setBusy(true)
        binding.textPinMessage.setText(R.string.pin_checking)
        val api = OdooApi(prefs.odooUrl, prefs.apiKey)
        Thread {
            val result = api.verifyPin(pin)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) handle(result)
            }
        }.start()
    }

    private fun handle(result: PinResult) {
        setBusy(false)
        binding.inputPin.text?.clear()
        binding.textPinMessage.text = when (result) {
            PinResult.Ok -> {
                prefs.pinVerified = true
                PinSession.unlock()
                finish()
                return
            }
            PinResult.InvalidKey -> {
                resetConnection(R.string.pin_invalid_key)
                return
            }
            PinResult.NotSet -> getString(R.string.pin_not_set)
            is PinResult.Wrong -> getString(R.string.pin_wrong, result.attemptsLeft)
            is PinResult.Locked -> getString(
                R.string.pin_locked,
                (result.retryAfterSeconds + SECONDS_IN_MINUTE - 1) / SECONDS_IN_MINUTE,
            )
            PinResult.Offline -> getString(R.string.pin_offline)
            PinResult.ServerError -> getString(R.string.pin_server_error)
        }
        // Manzil xato yozilgan bo'lsa bu ekrandan chiqishning boshqa yo'li yo'q.
        // Faqat hali tasdiqlanmagan ulanishda: tasdiqlangan telefonda xodim
        // tugma bosib yuborishni to'xtatib qo'ya olmasin.
        val connectionProblem = result is PinResult.Offline || result is PinResult.ServerError
        binding.btnReconfigure.visibility =
            if (connectionProblem && !prefs.pinVerified) View.VISIBLE else View.GONE
    }

    private fun confirmReconfigure() {
        AlertDialog.Builder(this)
            .setMessage(R.string.pin_reconfigure_confirm)
            .setPositiveButton(R.string.pin_reconfigure_ok) { _, _ ->
                resetConnection(R.string.msg_enter_connection)
            }
            .setNegativeButton(R.string.pin_reconfigure_cancel, null)
            .show()
    }

    /** Kalit o'chadi, shuning uchun bu yo'l PIN'ni chetlab o'tishga xizmat qilmaydi. */
    private fun resetConnection(message: Int) {
        prefs.apiKey = ""
        prefs.pinVerified = false
        PinSession.lock()
        PinSession.requestFormReset(message)
        finish()
    }

    private fun setBusy(busy: Boolean) {
        binding.inputPin.isEnabled = !busy
        binding.btnPin.isEnabled = false
    }

    private companion object {
        const val PIN_LENGTH = 4
        const val SECONDS_IN_MINUTE = 60
    }
}
