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

// Payload decryption keys. These used to be literals in WhiteDnsConfig.kt, which put them in every
// APK and in the git history. They are injected at build time instead — see secrets.properties.example.
val payloadSecretsFile = rootProject.file("secrets.properties")
val payloadSecrets = Properties().apply {
    if (payloadSecretsFile.isFile) {
        payloadSecretsFile.inputStream().use { load(it) }
    }
}

fun payloadSecret(propertyName: String, environmentName: String): String {
    return System.getenv(environmentName)?.takeIf { it.isNotBlank() }
        ?: payloadSecrets.getProperty(propertyName)?.takeIf { it.isNotBlank() }
        ?: ""
}

fun httpsBuildUrl(environmentName: String, defaultValue: String): String {
    return (System.getenv(environmentName)?.takeIf { it.isNotBlank() } ?: defaultValue).also {
        require(it.startsWith("https://")) { "$environmentName must use HTTPS" }
    }
}

val mihomoSubscriptionUrl = httpsBuildUrl(
    "WHITEDNS_MIHOMO_SUBSCRIPTION_URL",
    "https://raw.githubusercontent.com/iampedii/whitedns-sub/refs/heads/main/mihomo.yaml",
)
val encryptedIpListUrl = httpsBuildUrl(
    "WHITEDNS_ENCRYPTED_IP_LIST_URL",
    "https://whitedns-encrypted-ip-list.whitedns.workers.dev/v1/results/ips/encrypted",
)
val mihomoSubscriptionKey = payloadSecret("mihomoSubscriptionKey", "WHITEDNS_MIHOMO_SUBSCRIPTION_KEY")
val encryptedIpListKey = payloadSecret("encryptedIpListKey", "WHITEDNS_ENCRYPTED_IP_LIST_KEY")
val hasPayloadSecrets = mihomoSubscriptionKey.isNotBlank() && encryptedIpListKey.isNotBlank()

fun buildConfigStringLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\u0024") + "\""

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
        minSdk = 26
        targetSdk = 35
        versionCode = 78
        versionName = "1.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MIHOMO_SUBSCRIPTION_URL", buildConfigStringLiteral(mihomoSubscriptionUrl))
        buildConfigField("String", "ENCRYPTED_IP_LIST_URL", buildConfigStringLiteral(encryptedIpListUrl))
        buildConfigField("String", "MIHOMO_SUBSCRIPTION_KEY", buildConfigStringLiteral(mihomoSubscriptionKey))
        buildConfigField("String", "ENCRYPTED_IP_LIST_KEY", buildConfigStringLiteral(encryptedIpListKey))

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
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

    sourceSets {
        getByName("main") {
            jniLibs.srcDir("src/main/jniLibs")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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

    testImplementation("junit:junit:4.13.2")
}

val buildFlClashCore = tasks.register<Exec>("buildFlClashCore") {
    workingDir = rootProject.projectDir
    commandLine(rootProject.file("scripts/build-flclash-core.sh").absolutePath)
}

tasks.matching { task ->
    task.name.startsWith("configureCMake") ||
        task.name.startsWith("buildCMake") ||
        task.name.startsWith("externalNativeBuild") ||
        task.name.startsWith("merge") && task.name.endsWith("JniLibFolders")
}.configureEach {
    dependsOn(buildFlClashCore)
}

tasks.register("checkFlClashCore") {
    dependsOn(buildFlClashCore)
    doLast {
        val missing = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64").filter { abi ->
            !file("src/main/jniLibs/$abi/libclash.so").isFile ||
                !file("src/main/cpp/includes/$abi/libclash.h").isFile ||
                !file("src/main/cpp/includes/$abi/bride.h").isFile
        }
        if (missing.isNotEmpty()) {
            throw GradleException("Missing FlClash core output for ABI(s): ${missing.joinToString()}")
        }
    }
}

val validateReleaseInputs = tasks.register("validateReleaseInputs") {
    dependsOn("checkFlClashCore")
    doLast {
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
        if (!hasPayloadSecrets) {
            throw GradleException(
                "Payload decryption keys are not configured. Set WHITEDNS_MIHOMO_SUBSCRIPTION_KEY " +
                    "and WHITEDNS_ENCRYPTED_IP_LIST_KEY, or create secrets.properties from " +
                    "secrets.properties.example.",
            )
        }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(validateReleaseInputs)
}
