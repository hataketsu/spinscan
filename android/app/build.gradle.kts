// 'java' resolves to Gradle's java extension inside a build script, so the
// digest class is imported rather than named inline.
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.npay.collmap"
    compileSdk = 34

    defaultConfig {
        applicationId = "vn.npay.collmap"
        minSdk = 26
        targetSdk = 34
        // The rig serves dist/latest.json beside the APK and the phone compares
        // this number against it, so the version lives here and nowhere else.
        versionCode = 10
        versionName = "2.0"

        /*
         * The app is Vietnamese and its strings are not translated. Material and
         * AppCompat ship theirs in ~80 locales, and every one of them rides along
         * in the APK that the rig serves over WiFi to a phone standing next to
         * it. English is kept as the fallback for a device set to neither.
         */
        resourceConfigurations += listOf("en", "vi")
    }

    signingConfigs {
        /*
         * Pinned to the developer's own debug keystore on purpose.
         *
         * This app updates itself over the air: the phone downloads the APK the
         * rig is serving and hands it to PackageInstaller, which refuses an
         * update signed by a different key. Every copy already in the field was
         * signed with ~/.android/debug.keystore by the old build.sh, so a build
         * that signed with anything else would not install over them -- it would
         * strand every phone that already has the app and need a manual
         * uninstall at each one.
         *
         * AGP's implicit debug config points at the same file, but it silently
         * *generates* a fresh keystore when the file is missing. That new key
         * would break exactly the same way, quietly. Naming the file here turns
         * that into a build failure instead.
         */
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
        getByName("release") {
            // Same key, same reason: an OTA has to be installable over what is
            // already out there.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

/**
 * Publishes the APK the rig serves over the air.
 *
 * The phone fetches /api/app/latest, compares version_code against its own and,
 * if it is behind, downloads /api/app/download and checks the SHA-256 declared
 * here before handing the file to the system installer. Both files therefore
 * have to be written together and from the same build -- a manifest describing
 * an APK that is not the one beside it fails the hash check on the phone and
 * looks like a corrupt download.
 *
 * This is the one job the old build.sh did that Gradle does not do by itself.
 */
tasks.register("publishOta") {
    dependsOn("assembleDebug")
    val apkProvider = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val dist = rootProject.layout.projectDirectory.dir("../dist").asFile
    val code = android.defaultConfig.versionCode
    val name = android.defaultConfig.versionName
    doLast {
        val apk = apkProvider.get().asFile
        dist.mkdirs()
        val out = File(dist, "collmap.apk")
        apk.copyTo(out, overwrite = true)
        val bytes = out.readBytes()
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { b -> "%02x".format(b) }
        File(dist, "latest.json").writeText(
            """
            {
             "version_code": $code,
             "version_name": "$name",
             "url": "/api/app/download",
             "sha256": "$sha",
             "size": ${bytes.size},
             "notes": ""
            }
            """.trimIndent() + "\n")
        println("published dist/collmap.apk  $name (code $code)  ${bytes.size / 1024} KB")
        println("          sha256 $sha")
    }
}
