import com.android.build.api.variant.FilterConfiguration
import com.android.build.api.variant.VariantOutputConfiguration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// local.properties is git-ignored; it carries sdk.dir and secrets such as dashcam.rsaKey.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use { load(it) }
}

// dashcam.rsaKey is expected as single-line Base64; quotes, backslashes and line breaks (e.g. a pasted PEM)
// are escaped so BuildConfig.java still compiles.
fun String.asBuildConfigString() = "\"" +
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

// applicationId and namespace; google-services.json must contain a client for this package.
val appPackage = "me.ri3d.cam"

// Firebase without the google-services plugin (accounts): app/google-services.json is git-ignored and only read if
// present. Every value stays "" when the file is missing or still holds the example's PLACEHOLDER values, so Firebase
// is never initialised and the app runs in guest mode only.
val googleServices: Any? = providers.fileContents(layout.projectDirectory.file("google-services.json")).asText.orNull
    ?.let { groovy.json.JsonSlurper().parseText(it) }
fun Any?.json(key: String): Any? = (this as? Map<*, *>)?.get(key)
val firebaseClient = (googleServices.json("client") as? List<*>)
    ?.firstOrNull { it.json("client_info").json("android_client_info").json("package_name") == appPackage }
val firebaseWebClient = ((firebaseClient.json("oauth_client") as? List<*>).orEmpty() +
    (firebaseClient.json("services").json("appinvite_service").json("other_platform_oauth_client") as? List<*>).orEmpty())
    .firstOrNull { it.json("client_type") == 3 }
fun firebaseValue(value: Any?) = (value as? String)?.takeUnless { "PLACEHOLDER" in it }.orEmpty().asBuildConfigString()

// Version from gradle.properties; bump rule in docs/RELEASE.md.
val appVersionCode = providers.gradleProperty("myforthing.versionCode").get().toInt()
val appVersionName = providers.gradleProperty("myforthing.versionName").get()

// Release signing from local.properties (keystore outside git, docs/RELEASE.md). Without all four keys the release
// build stays unsigned: Android cannot install it, but it still builds (e.g. on a machine without the keystore).
val releaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .associateWith { localProperties.getProperty("release.$it")?.takeIf(String::isNotBlank) }
val releaseSigned = releaseSigning.values.all { it != null }
if (!releaseSigned) {
    logger.warn(
        "My Forthing: release APKs will be UNSIGNED - local.properties lacks " +
            releaseSigning.filterValues { it == null }.keys.joinToString { "release.$it" } + " (see docs/RELEASE.md)."
    )
}

android {
    namespace = appPackage
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = appPackage
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DASHCAM_RSA_KEY", localProperties.getProperty("dashcam.rsaKey", "").asBuildConfigString())
        buildConfigField("String", "FIREBASE_PROJECT_ID", firebaseValue(googleServices.json("project_info").json("project_id")))
        buildConfigField("String", "FIREBASE_APP_ID", firebaseValue(firebaseClient.json("client_info").json("mobilesdk_app_id")))
        buildConfigField("String", "FIREBASE_API_KEY", firebaseValue((firebaseClient.json("api_key") as? List<*>)?.firstOrNull().json("current_key")))
        buildConfigField("String", "FIREBASE_STORAGE_BUCKET", firebaseValue(googleServices.json("project_info").json("storage_bucket")))
        buildConfigField("String", "FIREBASE_WEB_CLIENT_ID", firebaseValue(firebaseWebClient.json("client_id")))
    }

    signingConfigs {
        if (releaseSigned) create("release") {
            storeFile = rootProject.file(releaseSigning.getValue("storeFile")!!)
            storePassword = releaseSigning.getValue("storePassword")
            keyAlias = releaseSigning.getValue("keyAlias")
            keyPassword = releaseSigning.getValue("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // ponytail: no R8, so the APK carries unused code. Enabling it needs keep rules (Credential Manager /
            // googleid, ML Kit, LiteRT, Room/Hilt reflection) and a full re-test of the release build.
            optimization {
                enable = false
            }
        }
    }
    // One APK per phone ABI for release; the DSL has no per-build-type switch, so androidComponents below keeps
    // only the universal APK (all ABIs, incl. x86_64 for the emulator) for debug and drops it for release.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        generateLocaleConfig = true
    }
}

androidComponents {
    onVariants { variant ->
        val release = variant.buildType == "release"
        variant.outputs.forEach { output ->
            val universal = output.outputType == VariantOutputConfiguration.OutputType.UNIVERSAL
            output.enabled.set(universal != release)
            val abi = output.filters.firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            when {
                // keeps the documented path app/build/outputs/apk/debug/app-debug.apk
                !release -> output.outputFileName.set("app-${variant.name}.apk")
                releaseSigned && abi != null ->
                    output.outputFileName.set("MyForthing-$appVersionName-$appVersionCode-$abi.apk")
            }
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    // drive
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // accounts
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.androidx.exifinterface)
    // plates
    implementation(libs.mlkit.text.recognition)
    // enhance
    implementation(libs.litert)
    // dashcam (okhttp above, shared with drive)
    implementation(project(":recorder"))
    implementation(libs.androidx.lifecycle.process)
    // live
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    // media
    implementation(libs.androidx.media3.ui)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(testFixtures(project(":recorder"))) // dashcam: RecorderSimulator
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.work.testing) // media: download queue
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
