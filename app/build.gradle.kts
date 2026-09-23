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

// Private subscription endpoint configuration; no payload decryption keys are packaged.
val buildPropertiesFile = rootProject.file("secrets.properties")
val buildProperties = Properties().apply {
    if (buildPropertiesFile.isFile) {
        buildPropertiesFile.inputStream().use { load(it) }
    }
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
val privateMihomoSubscriptionUrl = (
    System.getenv("WHITEDNS_PRIVATE_MIHOMO_SUBSCRIPTION_URL")?.takeIf { it.isNotBlank() }
        ?: buildProperties.getProperty("privateMihomoSubscriptionUrl")?.takeIf { it.isNotBlank() }
        ?: ""
    ).also {
    require(it.isEmpty() || it.startsWith("https://")) {
        "WHITEDNS_PRIVATE_MIHOMO_SUBSCRIPTION_URL must use HTTPS"
    }
}
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
        versionCode = 84
        versionName = "1.6.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en", "fa")

        buildConfigField("String", "MIHOMO_SUBSCRIPTION_URL", buildConfigStringLiteral(mihomoSubscriptionUrl))
        buildConfigField(
            "String",
            "PRIVATE_MIHOMO_SUBSCRIPTION_URL",
            buildConfigStringLiteral(privateMihomoSubscriptionUrl),
        )

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
            isShrinkResources = true
            // CMake and Go already strip release binaries; generated .sym files only duplicate them.
            ndk.debugSymbolLevel = "NONE"
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
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
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
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(validateReleaseInputs)
}
