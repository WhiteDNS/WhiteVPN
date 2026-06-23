import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningPropertiesFile = rootProject.file("keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use { load(it) }
    }
}

fun releaseSigningValue(propertyName: String, environmentName: String): String? {
    return System.getenv(environmentName)?.takeIf { it.isNotBlank() }
        ?: releaseSigningProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }
        ?: releaseSigningProperties.getProperty("release.$propertyName")?.takeIf { it.isNotBlank() }
}

fun releaseStoreFile(path: String) = File(path).let { candidate ->
    if (candidate.isAbsolute) candidate else rootProject.file(path)
}

val releaseStoreFilePath = releaseSigningValue("storeFile", "WHITEDNS_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValue("storePassword", "WHITEDNS_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "WHITEDNS_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "WHITEDNS_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.whitedns.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.whitedns.vpn"
        minSdk = 23
        targetSdk = 35
        versionCode = 5
        versionName = "0.0.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation(platform("com.google.firebase:firebase-bom:34.15.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.json:json:20240303")
    implementation(libs.material)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    val libboxAar = file("libs/libbox.aar")
    if (libboxAar.exists()) {
        implementation(files(libboxAar))
    } else {
        compileOnly(project(":libbox-stub"))
    }

    testImplementation("junit:junit:4.13.2")
}

tasks.register("checkLibboxAar") {
    doLast {
        val libboxAar = file("libs/libbox.aar")
        if (!libboxAar.exists()) {
            logger.warn(
                "app/libs/libbox.aar is missing. The app sources compile with a stub, " +
                    "but the APK cannot run sing-box until scripts/build-libbox.sh creates the real AAR.",
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn("checkLibboxAar")
}

val validateReleaseInputs = tasks.register("validateReleaseInputs") {
    doLast {
        if (!file("libs/libbox.aar").isFile) {
            throw GradleException(
                "Release builds require app/libs/libbox.aar. Run scripts/build-libbox.sh first.",
            )
        }
        if (!hasReleaseSigning) {
            throw GradleException(
                "Release signing is not configured. Set WHITEDNS_RELEASE_STORE_FILE, " +
                    "WHITEDNS_RELEASE_STORE_PASSWORD, WHITEDNS_RELEASE_KEY_ALIAS, and " +
                    "WHITEDNS_RELEASE_KEY_PASSWORD, or create keystore.properties from " +
                    "keystore.properties.example.",
            )
        }
        val store = releaseStoreFile(releaseStoreFilePath!!)
        if (!store.isFile) {
            throw GradleException("Release keystore not found: ${store.absolutePath}")
        }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(validateReleaseInputs)
}
