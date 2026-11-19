import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// sherpa-onnx (Apache-2.0) ist nicht in Maven Central: das offizielle AAR wird beim ersten Build von den
// GitHub-Releases geladen, per SHA-256 geprüft und unter third_party/ zwischengespeichert (nicht im Git).
val sherpaVersion = libs.versions.sherpaOnnx.get()
val sherpaSha256 = "6420cfb86c2ef8462dadd77b2202b7430487bdfb2bc715e9ce2864871209a80e"
val sherpaAar: File = rootProject.file("third_party/sherpa-onnx-$sherpaVersion.aar")
fun sha256Of(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    }
    md.digest().joinToString("") { "%02x".format(it) }
}
if (!sherpaAar.isFile || sha256Of(sherpaAar) != sherpaSha256) {
    sherpaAar.parentFile.mkdirs()
    val tmp = File(sherpaAar.path + ".part")
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    logger.lifecycle("Lade $url …")
    URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    val actual = sha256Of(tmp)
    check(actual == sherpaSha256) { "SHA-256 von sherpa-onnx stimmt nicht: $actual" }
    tmp.renameTo(sherpaAar)
}

// Optionale Release-Signatur aus Umgebungsvariablen (GitHub-Secrets). Fehlen sie, bleibt das Release unsigniert.
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "de.open2ndbrain"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.open2ndbrain"
        minSdk = 29
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Moderne 64-Bit-Geräte. 32-Bit-Geräte sind für die Modelle zu schwach.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Zusätzlich x86_64 für den Emulator
            ndk { abiFilters += "x86_64" }
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Komprimiert in der APK (kleinerer Download); Android entpackt sie bei der Installation.
            useLegacyPackaging = true
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(files(sherpaAar))
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
