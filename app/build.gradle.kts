import java.net.URI
import java.io.InputStream
import java.io.OutputStream
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.glass.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.glass.launcher"
        minSdk = 33
        targetSdk = 36
        // Sürüm numarası her CI derlemesinde artar (GitHub çalışma numarası). Sabit kaldığında (10) Honor/MagicOS
        // uygulama simgesini ve adını sürüm numarasına göre önbellekte tuttuğu için yeni simge görünmüyordu.
        // Yerel derlemede 10 kalır; CI numarası her zaman 10'dan büyük olduğu için eskisinin üzerine kurulur.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = maxOf(10, (run ?: 0) + 10)
        versionName = "1.0." + (run ?: 0)
    }

    // Kişisel kullanım için sabit imza anahtarı: her derleme aynı anahtarla imzalanır, böylece yeni APK
    // eskisinin üzerine (kaldırmadan) kurulabilir. Anahtar depoda olduğu için yalnızca kişisel kullanım
    // içindir; uygulama mağazası için kullanılmamalıdır.
    signingConfigs {
        create("personal") {
            storeFile = rootProject.file("keystore/glass-launcher.jks")
            storePassword = "glasslauncher"
            keyAlias = "glass"
            keyPassword = "glasslauncher"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("personal")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        textReport = true
        textOutput = file("build/reports/lint.txt")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    // Klavyenin emoji paneli: Android'in tam emoji seçicisi
    implementation("androidx.emoji2:emoji2-emojipicker:1.5.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    // Shizuku: kullanıcı açarsa, ek uygulama olmadan kendine WRITE_SECURE_SETTINGS izni vermek için.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Klavye sözlüğü (Türkçe kelime sıklık listesi, FrequencyWords / OpenSubtitles 2018, CC BY-SA 4.0).
// Depoya konmaz; derlemede yoksa indirilir. İndirilemezse boş dosya yazılır (öneri/düzeltme kapalı kalır).
val trDict = layout.projectDirectory.file("src/main/assets/tr_words.txt")
val downloadTrDict by tasks.registering {
    outputs.file(trDict)
    onlyIf { !trDict.asFile.exists() || trDict.asFile.length() == 0L }
    doLast {
        val f = trDict.asFile
        f.parentFile.mkdirs()
        runCatching {
            URI("https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/tr/tr_50k.txt")
                .toURL().openStream().use { input: InputStream -> f.outputStream().use { out: OutputStream -> input.copyTo(out) } }
        }.onFailure { e: Throwable -> logger.warn("Türkçe sözlük indirilemedi: " + e.message); f.writeText("") }
    }
}
tasks.named("preBuild") { dependsOn(downloadTrDict) }
