import java.util.Properties
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Unsigned release builds are allowed for CI; distributed APKs require the team's release key.
val signingProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val backendOrigin = "https://cookx.local"
// Optional device/demo defaults. This ignored file is read only for release builds.
val demoProps = Properties().apply {
    val file = rootProject.file("demo-private.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun javaString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n") + "\""
val demoBaseUrl = demoProps.getProperty("compatibleBaseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1").trim().trimEnd('/')
// Keep raw credentials out of generated Java and DEX strings. The APK must still carry its
// decryption material: this prevents plaintext searches, not extraction by reverse engineering.
val demoBundle = Properties().apply {
    val rawKey = demoProps.getProperty("apiKey", "").trim()
    if (rawKey.isNotEmpty()) {
        val fingerprint = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
            .digest(("v1\n" + rawKey + "\n" + demoBaseUrl).toByteArray(Charsets.UTF_8)))
        val cache = rootProject.file(".gradle/cookx-demo-bundle.properties")
        if (cache.exists()) cache.inputStream().use { load(it) }
        if (getProperty("fingerprint") != fingerprint || getProperty("payload").isNullOrBlank() || getProperty("secret").isNullOrBlank()) {
            val secret = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, nonce))
                updateAAD(demoBaseUrl.toByteArray(Charsets.UTF_8))
            }
            setProperty("payload", Base64.getEncoder().encodeToString(nonce + cipher.doFinal(rawKey.toByteArray(Charsets.UTF_8))))
            setProperty("secret", Base64.getEncoder().encodeToString(secret.encoded))
            setProperty("fingerprint", fingerprint)
            cache.parentFile.mkdirs()
            cache.outputStream().use { store(it, "Private generated bundle; do not distribute or commit") }
        }
    }
}

android {
    namespace = "com.smartcooking.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.smartcooking.app.showcase"
        minSdk = 24
        targetSdk = 36
        versionCode = 23
        versionName = "2.2.3"
        buildConfigField("String", "DEFAULT_BACKEND_ORIGIN", "\"$backendOrigin\"")
        buildConfigField("String", "DEMO_ACCESS_PAYLOAD", "\"\"")
        buildConfigField("String", "DEMO_ACCESS_SECRET", "\"\"")
        buildConfigField("String", "DEMO_COMPATIBLE_BASE_URL", "\"https://dashscope.aliyuncs.com/compatible-mode/v1\"")
        // Phones only: the x86 ONNX Runtime libraries would add ~43 MB for emulators nobody tests on.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
                // v1 is kept for vendor installers that still check JAR signatures.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            buildConfigField("String", "DEMO_ACCESS_PAYLOAD", javaString(demoBundle.getProperty("payload", "")))
            buildConfigField("String", "DEMO_ACCESS_SECRET", javaString(demoBundle.getProperty("secret", "")))
            buildConfigField("String", "DEMO_COMPATIBLE_BASE_URL", javaString(demoBaseUrl))
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += "onnx"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("roborazzi.output.dir", rootProject.file("screenshots").absolutePath)
            }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.onnxruntime.android)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
