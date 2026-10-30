import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signieren: In CI kommen Keystore und Passwörter aus den GitHub-Secrets, lokal optional
// aus local.properties. Ohne beides wird mit dem Debug-Schlüssel signiert.
val localProps = Properties()
rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { localProps.load(it) }

val keystorePath = System.getenv("ANDROID_KEYSTORE_FILE") ?: localProps.getProperty("kifossk_key.storeFile")
val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: localProps.getProperty("kifossk_key.storePassword", "")
val keystoreAlias = System.getenv("ANDROID_KEY_ALIAS") ?: localProps.getProperty("kifossk_key.keyAlias", "familienboard")
val keystoreKeyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: localProps.getProperty("kifossk_key.keyPassword", keystorePassword)
val stableSigning = !keystorePath.isNullOrBlank() && keystorePassword.isNotBlank()

android {
    namespace = "com.shinydiscoballsdev.kifossk"
    compileSdk = 35

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "de.eswinkelt.kifossk"
        minSdk = 24
        //noinspection EditedTargetSdkVersion
        targetSdk = 35
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 11
        versionName = System.getenv("VERSION_NAME") ?: "1.1.5-familienboard"
    }

    applicationVariants.all {
        outputs.all {
            val variantName = if (buildType.name == "release") "release" else "debug"
            val fileName = "kiFOSSk-${versionName}-${variantName}.apk"
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = fileName
        }
    }

    signingConfigs {
        create("release") {
            if (stableSigning) {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        // Auch der Debug-Build bekommt den festen Schlüssel, damit Updates in-place gehen
        // und das Wandtablet per run-as gewartet werden kann.
        debug {
            signingConfig = signingConfigs.getByName(if (stableSigning) "release" else "debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName(if (stableSigning) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.9.0")
}