plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.serif.tvkumanda"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.serif.tvkumanda"
        minSdk = 26
        targetSdk = 34
        // Sürüm, derleme akışının hesapladığı sıradaki numaradan gelir (son sürüm 0.16 ise 0.17).
        val build = System.getenv("APP_VERSION")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.$build"
    }

    // İmza anahtarı depoda DURMAZ. GitHub Actions'ta Secrets'tan geçici dosyaya çözülür,
    // yol ve parola ortam değişkenleriyle verilir. Yerelde bu değişkenler yoksa
    // release derlemesi imzasız kalır (yalnızca debug derlemesi çalıştırılabilir).
    val ksFile = System.getenv("ANDROID_KEYSTORE_FILE")
    val ksPass = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    signingConfigs {
        if (ksFile != null && ksPass != null) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = ksPass
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "tvkumanda"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
        compose = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ADB istemcisi (Wi-Fi üzerinden TV'ye bağlanır)
    implementation("dev.mobile:dadb:1.2.10")
}
