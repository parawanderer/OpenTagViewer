plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.android.libraries.mapsplatform.secrets.gradle.plugin)
    alias(libs.plugins.gradle.lombok)
    alias(libs.plugins.chaquopy)
}

secrets {
    // To add your Maps API key to this project:
    // 1. If the secrets.properties file does not exist, create it in the same folder as the local.properties file.
    // 2. Add these lines, where YOUR_API_KEY is your API key:
    //        MAPS_API_KEY=YOUR_API_KEY
    // 
    // How to get Google Maps API Key:
    //    - Visit: https://console.cloud.google.com/google/maps-apis/
    //
    // How to get AMap API Key (高德地图 API Key):
    //    - Visit: https://console.amap.com/dev/key/app
    //    - Guide: https://lbs.amap.com/api/android-sdk/guide/create-project/get-key
    propertiesFileName = "secrets.properties"

    // A properties file containing default secret values. This file can be
    // checked in version control.
    defaultPropertiesFileName = "local.defaults.properties"
}

android {
    namespace = "dev.wander.android.opentagviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.wander.android.opentagviewer"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // Apple's ADI libraries are C, and their entry points are obfuscated names
                // that move between APK builds, so they are dlopen'd and resolved at runtime
                // rather than bound as JNI methods. See app/src/main/cpp/.
                cppFlags += "-std=c++17"

                // Align our own libraries to 16 KB pages, which Android 15+ devices with
                // 16 KB page sizes require. This is explicit because r27 does NOT do it by
                // default - measured, not assumed: an r27 build without this flag produces
                // PT_LOAD segments at 0x1000. r28 makes it the default and this can go.
                arguments += "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
            }
        }

        // Export the Room schema as JSON on every build. These are committed (see
        // app/schemas/) so schema changes show up as a reviewable diff, and so
        // MigrationTestHelper can build an old database to migrate from.
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf("room.schemaLocation" to "$projectDir/schemas")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Pinned so the toolchain does not depend on whichever NDK a given machine happens to
    // have installed. Note r27 does not align to 16 KB pages on its own - the linker flag in
    // defaultConfig.externalNativeBuild does that. See #47.
    ndkVersion = "27.0.12077973"

    // Makes the exported schemas readable by instrumented tests at runtime, which is
    // how MigrationTestHelper creates a v1 database to run MIGRATION_1_2 against.
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "release-keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // Keeps the launcher name as-is for real installs.
            manifestPlaceholders["appLabel"] = "@string/app_name"
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true

            // Install debug builds alongside a release install rather than colliding with
            // it. Without this, both share the applicationId but are signed with different
            // keys, so installing a debug build over a real one fails with
            // INSTALL_FAILED_UPDATE_INCOMPATIBLE and the only way forward is to uninstall -
            // which permanently destroys the user's imported beacons and location history.
            // allowBackup is false, so there is no backup to restore from either.
            //
            // Note: a Maps API key restricted to the release package name will not authorise
            // this one. Add "dev.wander.android.opentagviewer.debug" (with the debug keystore
            // SHA-1) to the key's restrictions if you need maps to render in debug builds.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"

            // Distinct launcher name, otherwise a debug install sits next to a real one
            // with an identical icon and label and there is no way to tell them apart.
            manifestPlaceholders["appLabel"] = "OpenTagViewer (debug)"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        dataBinding = true
        buildConfig = true
    }

    androidResources {
        // generateLocaleConfig = true
    }

    testOptions {
        // Espresso waits for a window to hold focus and stop laying out before it will touch
        // it, and a running animation never satisfies that - it fails with
        // RootViewWithoutFocusException after ten seconds rather than with anything about the
        // test. Turning them off is the documented requirement for Espresso, not a workaround.
        animationsDisabled = true

        managedDevices {
            localDevices {
                // Gradle provisions, boots, and tears down this emulator itself.
                //
                // Running against an emulator you started by hand is unreliable: the Android
                // Gradle Plugin holds its ADB connection inside the Gradle daemon and reuses
                // it between invocations, so once the emulator's adb daemon goes stale the
                // next run fails to install or hangs, with an error that has nothing to do
                // with the code. A managed device is created fresh per run, so there is no
                // connection left over to go stale. It is also what CI can run unattended.
                //
                // aosp-atd is a stripped-down image built for tests: no Play Services, and no
                // Maps as a result. Fine here, because none of the instrumented tests start
                // MapsActivity - they cover the database migrations, the repositories and the
                // keystore. Anything that needs Maps has to move to a "google" image.
                create("testEmulator") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
}

lombok {
    version = libs.versions.lombokVersion.get()
}

// FindMy >= 0.9 depends on anisette, which depends on unicorn (a CPU emulator used only
// for *local* Anisette). Chaquopy cannot build unicorn's native code for Android, so the
// whole dependency tree fails to resolve without a stand-in. We build a pure-Python stub
// wheel from the real sources in app/stubs/unicorn/ rather than checking in a prebuilt
// .whl - a binary artifact impersonating a well-known dependency is hard to audit.
val unicornStubWheel = layout.buildDirectory.file(
    "generated/stub-wheels/unicorn-2.1.1-py3-none-any.whl"
)

/**
 * Locate a usable Python 3 interpreter.
 *
 * On Windows, `python3` is usually a zero-byte Microsoft Store "App Execution Alias" that
 * hangs indefinitely when run non-interactively instead of failing, so candidates are
 * ordered per-platform and stub aliases are filtered out by inspecting the file rather
 * than by executing it.
 */
fun resolvePythonExecutable(): String {
    providers.gradleProperty("pythonExecutable").orNull?.let { return it }

    val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val names = if (isWindows) listOf("python.exe", "python3.exe") else listOf("python3", "python")

    val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator).filter { it.isNotBlank() }
    for (name in names) {
        for (dir in pathDirs) {
            val candidate = File(dir, name)
            if (!candidate.isFile || !candidate.canExecute()) continue
            // Store aliases are zero-length reparse points; executing one blocks forever.
            if (candidate.length() == 0L) continue
            if (candidate.absolutePath.contains("WindowsApps", ignoreCase = true)) continue
            return candidate.absolutePath
        }
    }

    throw GradleException(
        "No usable Python 3 interpreter found on PATH (looked for ${names.joinToString(", ")}). " +
        "Chaquopy needs one to build the unicorn stub wheel. " +
        "Install Python 3 or pass -PpythonExecutable=/path/to/python."
    )
}

// A real task rather than configuration-time work: this used to run on every Gradle
// invocation, including every IDE sync, which blocked them.
val generateUnicornStubWheel by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the pure-Python unicorn stub wheel that lets FindMy 0.9.x resolve."

    val script = rootProject.file("scripts/build_unicorn_stub_wheel.py")

    inputs.dir(layout.projectDirectory.dir("stubs/unicorn")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(script).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(unicornStubWheel)
    outputs.cacheIf { true }

    commandLine(resolvePythonExecutable(), script.absolutePath, unicornStubWheel.get().asFile.absolutePath)
}

// Chaquopy installs from the wheel path, so it must exist before pip runs.
tasks.matching { it.name.contains("PythonRequirements") || it.name.contains("PythonReqs") }
    .configureEach { dependsOn(generateUnicornStubWheel) }

// Deliberately NOT wired into assembleDebug/assembleRelease.
//
// Its output is committed source, so running it on every build would either dirty the working
// tree whenever somebody pushes a commit or changes their GitHub avatar, or make an offline
// build fail. Refreshing it is a repository event, not a build event: the scheduled workflow
// in .github/workflows/update-contributors.yml runs this and opens a PR when the list moves.
// Run it by hand any time with ./gradlew updateContributors.
val updateContributors by tasks.registering(Exec::class) {
    group = "build"
    description = "Regenerates the contributor list and avatars bundled into the Information page."

    val script = rootProject.file("scripts/fetch_contributors.py")

    inputs.file(script).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(layout.projectDirectory.file("src/main/assets/contributors.json"))
    outputs.dir(layout.projectDirectory.dir("src/main/assets/contributors"))
    // Talks to the network, so its result is not reproducible from its inputs.
    outputs.upToDateWhen { false }

    commandLine(resolvePythonExecutable(), script.absolutePath)
}

chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            // SEE: https://chaquo.com/chaquopy/doc/current/android.html#android-requirements
            install(unicornStubWheel.get().asFile.absolutePath)

            // TEMPORARY: a fork, until the iCloud keychain export work is merged upstream
            // and released to PyPI. Then this goes back to a plain `install("FindMy==<x>")`.
            //
            // A branch, deliberately, while that branch is under active development - a
            // commit pin would need moving on every change to it. The cost is that the build
            // is not reproducible: two builds of the same commit of *this* repo can ship
            // different Python. Acceptable now, not acceptable to release.
            //
            // **Pin to a commit before any build that leaves this machine**, by appending
            // `@<sha>` in place of the branch name.
            //
            // Note for whoever bumps it: 0.10.x adds protobuf, which publishes a native
            // wheel for desktop platforms and a pure-Python `py3-none-any` one as well.
            // There is no Android wheel, so pip falls back to the pure-Python build - which
            // is correct but markedly slower. The messages here are small enough not to care.
            install("git+https://github.com/parawanderer/FindMy.py@feat/icloud-keychain-export")

            install("NSKeyedUnArchiver==1.5")
        }
    }
    productFlavors {}
    sourceSets {}
}

dependencies {

    implementation(libs.preference)
    implementation(libs.activity)
    implementation(libs.annotation)
    compileOnly(libs.projectlombok)
    implementation(libs.rxjava3)

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.play.services.maps)
    implementation(libs.android.room)
    implementation(libs.android.room.paging)
    implementation(libs.fasterxml.jackson.core)
    implementation(libs.fasterxml.jackson.databind)
    implementation(libs.fasterxml.jackson.annotations)
    implementation(libs.fasterxml.jackson.yaml)
    implementation(libs.networknt.json.schema.validator)
    implementation(libs.datastore.prefs)
    implementation(libs.datastore.prefs.rxjava3)
    implementation(libs.google.cronet)
    implementation(libs.retrofit)
    implementation(libs.retrofit.jackson)
    implementation(libs.retrofit.rxjava3)
    implementation(libs.google.cronet.retrofit)
    implementation(libs.cronet.embedded)
    implementation(libs.google.play.location)
    implementation(libs.google.places)
    implementation(libs.androidx.emoji)
    implementation(libs.androidx.emoji.views)
    implementation(libs.androidx.emoji.views.helper)
    implementation(libs.androidx.emoji.picker)

    // 高德地图SDK - Android 3D地图 V9.8.3
    // 参考文档：https://lbs.amap.com/api/android-sdk/gettingstarted
    // 注意：3D地图SDK已包含定位功能，无需单独引入location SDK
    implementation(libs.amap.map3d)
    implementation(libs.zip4j)

    testImplementation(libs.junit)
    testImplementation(libs.android.room.testing)
    testCompileOnly(libs.projectlombok)

    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    // Lets a test assert which screen the app moved to, and stop it actually going there.
    // MapsActivity is the end of the sign-in flow and needs Play Services, which the aosp-atd
    // managed device does not have.
    androidTestImplementation(libs.espresso.intents)
    androidTestImplementation(libs.android.room.testing)

    annotationProcessor(libs.projectlombok)
    annotationProcessor(libs.android.room.compiler)

    testAnnotationProcessor(libs.projectlombok)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
