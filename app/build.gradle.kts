plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "uz.todoo.callbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "uz.todoo.callbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.1.0"
    }

    // Doimiy imzo kaliti. Fayl repoda saqlanmaydi — CI uni GitHub Secrets dan
    // ochadi (KEYSTORE_BASE64). Kalit doimiy bo'lgani uchun yangilanish eski
    // ilova ustiga o'rnatiladi va sozlamalar saqlanadi.
    val keystoreFile = rootProject.file("release.keystore")
    val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")

    signingConfigs {
        if (keystoreFile.exists() && !keystorePassword.isNullOrBlank()) {
            create("release") {
                storeFile = keystoreFile
                storeType = "PKCS12"
                storePassword = keystorePassword
                keyAlias = System.getenv("KEY_ALIAS") ?: "callbridge"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Keystore yo'q bo'lsa null qoladi va release build yig'ilmaydi —
            // CI bunday holatda debug build ga tushadi
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
