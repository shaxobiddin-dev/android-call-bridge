# Call Bridge

Android telefondagi **har bir kirish/chiqish qo'ng'irog'ini** avtomatik Odoo'ga
yozadi. Telefonning **o'z dialer'idan** qilingan qo'ng'iroqlar ham qamrab
olinadi — Android hamma qo'ng'iroqni tizim `CallLog` iga yozadi.

Odoo tomoni: [`voip_android_bridge`](https://github.com/shaxobiddin-dev/100)
moduli (`voip.call` yozuvi + chatter'ga audio ilova).

## Nima ishlaydi

| | |
|---|---|
| Chiquvchi / kiruvchi / javobsiz qo'ng'iroq | ✅ |
| Raqam, vaqt, davomiylik, yo'nalish | ✅ |
| Internet yo'q bo'lsa navbatda saqlash | ✅ (WorkManager) |
| Telefon o'chib-yonsa o'zi tiklanadi | ✅ (BootReceiver) |
| Audio yozuv | ⚠️ telefonning **o'z dialer'i** yozsa |
| WhatsApp / Telegram qo'ng'iroqlari | ❌ CallLog'ga tushmaydi |

### Audio haqida

Android 10 (2019) dan boshlab uchinchi tomon ilovasi qo'ng'iroq audiosini
**o'zi yoza olmaydi** (`VOICE_CALL` manbasi root'siz yopiq). Shuning uchun bu
ilova faqat OEM dialer yozgan faylni **qidiradi**. Papka brendga qarab har xil,
shuning uchun qo'lda sozlanmaydi: birinchi topilgan papka eslab qolinadi.

## O'rnatish (har telefonda bir marta)

1. **APK'ni yuklab ol** — GitHub → Actions → oxirgi "Build APK" → `call-bridge-apk`
2. O'rnat (noma'lum manbadan o'rnatishga ruxsat berish kerak bo'lishi mumkin)
3. Ilovani och va ketma-ket bajar:
   - **Odoo URL** va **API key** kirit → **Saqlash**
   - **1. Ruxsatlarni berish**
   - **2. Fayllarga kirish** (audio kerak bo'lsa)
   - **3. Batareya cheklovini o'chirish** ← bunisiz Xiaomi/Samsung ilovani fonda o'ldiradi
4. **Sinov: oxirgi qo'ng'iroqni yuborish** tugmasi bilan tekshir

API key: Odoo → Preferences → **Security** → **Add API Key** →
Duration **"Persistent Key"** (default "1 Day" — o'zgartirish shart).

## Qanday ishlaydi

```
Qo'ng'iroq tugadi
   → PhoneStateReceiver (PHONE_STATE = IDLE)
   → CallSyncWorker 15 s dan keyin
      → CallLog dan yangi yozuvlarni o'qiydi
      → POST /voip/android/push   (Bearer API key)
      → audio faylni qidiradi → POST /voip/android/recording/<id>
```

Zaxira sifatida har 15 daqiqada davriy tekshiruv ishlaydi — broadcast
o'tkazib yuborilgan bo'lsa ham qo'ng'iroq yo'qolmaydi.

## Yig'ish

Lokalda hech narsa o'rnatish shart emas — har `main` ga push'da GitHub Actions
APK yig'adi. Lokal yig'ish uchun: JDK 17 + Android SDK 35 + Gradle 8.11.

```bash
gradle assembleDebug
```
