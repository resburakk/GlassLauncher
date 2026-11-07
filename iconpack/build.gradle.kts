plugins {
    id("com.android.application")
}

// "Cam 27" simge paketi: kodsuz, yalnızca simgeler ve appfilter.xml. Launcher ile aynı kişisel anahtarla imzalanır.
android {
    namespace = "dev.glass.cam27"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.glass.cam27"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("personal") {
            storeFile = rootProject.file("keystore/glass-launcher.jks")
            storePassword = "glasslauncher"
            keyAlias = "glass"
            keyPassword = "glasslauncher"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("personal") }
        release {
            // Simgeler yalnızca appfilter.xml'den adla bulunur: kaynak küçültme kapalı (yoksa silinirdi).
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
