plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ilian.xtreamplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ilian.xtreamplayer"
        minSdk = 26
        targetSdk = 35
        // Numéro de version = numéro de build GitHub : l'appli compare directement avec la dernière version publiée.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 6
        versionCode = build
        versionName = "6.0 · build $build"
        // Sticks Android TV / Fire TV : processeurs ARM uniquement (garde l'appli légère)
        // (sauf pour les captures d'écran automatiques, qui tournent sur un émulateur x86)
        if (System.getenv("ALL_ABIS") == null) {
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
        }
    }

    // Clé de signature fixe : toutes les versions portent la même signature, donc les mises à jour
    // s'installent par-dessus sans perdre les données. Le fichier est chiffré dans le dépôt
    // (signing/release.p12.enc) et déchiffré par GitHub avec le secret SIGNING_PASSWORD.
    val signingStore = System.getenv("SIGNING_STORE")
    signingConfigs {
        create("fixed") {
            if (signingStore != null) {
                storeFile = file(signingStore)
                storeType = "pkcs12"
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "xtream"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sans la clé (compilation locale) : clé de debug
            signingConfig = signingConfigs.getByName(if (signingStore != null) "fixed" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-extractor:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Décodeurs audio FFmpeg de secours (Dolby AC3 / E-AC3, DTS, TrueHD…), version Jellyfin publiée sur Maven Central
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    implementation("androidx.media3:media3-decoder:$media3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.core:core:1.13.1")
    implementation("com.google.zxing:core:3.5.3")
}
