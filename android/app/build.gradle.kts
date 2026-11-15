import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

/** Reads a git-ignored properties file from the android/ root, or returns empty properties. */
fun localProps(name: String): Properties = Properties().apply {
    val file = rootProject.file(name)
    if (file.isFile) file.inputStream().use { load(it) }
}

val localProperties = localProps("local.properties")
val keystoreProperties = localProps("keystore.properties")
val hasReleaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !keystoreProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "ir.fm24.app"
    // Android 17 (API 37): latest stable platform. Both store flavors inherit these values.
    compileSdk = 37
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "ir.fm24.app"
        minSdk = 24 // required by AndroidX WebKit 1.18
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        // IPinfo Lite token is supplied locally (android/local.properties: ipinfo.token=...)
        // and is never committed. Without it the country notice is disabled.
        val ipinfoToken = localProperties.getProperty("ipinfo.token", "").trim()
        buildConfigField("String", "IPINFO_TOKEN", "\"${ipinfoToken.replace("\"", "")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // One flavor per store. Shared code lives in src/main; anything store-specific goes in
    // src/bazaar or src/myket. Both inherit the SDK levels above until a store needs otherwise.
    flavorDimensions += "store"
    productFlavors {
        create("bazaar") { dimension = "store" }
        create("myket") { dimension = "store" }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.material)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
}
