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

/** The ABIs every build carries unless asked otherwise. An emulator is the first, a phone the second. */
val supportedAbis = listOf("arm64-v8a", "x86_64")

/**
 * `-PotvAbi=x86_64` to build one ABI instead of both. Null when nobody asked.
 *
 * Validated here rather than passed through: a typo would otherwise produce an APK with no
 * native libraries at all, which installs perfectly happily and then dies at the first Chaquopy
 * call - a far worse afternoon than a failed build.
 */
val requestedAbis: List<String>? = providers.gradleProperty("otvAbi").orNull
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.onEach { abi ->
        require(abi in supportedAbis) { "-PotvAbi=$abi is not one of $supportedAbis" }
    }

// **A release must never be built with otvAbi set.**
//
// `providers.gradleProperty` reads gradle.properties as well as -P, including the one in
// ~/.gradle. So somebody who tires of typing -PotvAbi=x86_64 and puts it there gets what they
// wanted for every local run, and also a release APK that installs on no phone anybody owns -
// with nothing to see in the build log, because it succeeded.
//
// Refused rather than silently ignored, so the flag never means two different things depending
// on which task is run.
//
// **Checked against the task graph, not in `beforeVariants`.** That hook runs for every variant
// whatever was asked for, so the release check there failed `assembleDebug` as well - which is
// the one command this property exists to serve. The graph knows what is actually going to be
// built, which is the question being asked.
if (requestedAbis != null) {
    gradle.taskGraph.whenReady {
        val releaseTask = this.allTasks.firstOrNull { it.name.contains("Release") }
        check(releaseTask == null) {
            "otvAbi is set to ${requestedAbis.joinToString(",")}, but this build runs " +
                "${releaseTask?.path} and a release must carry every ABI in $supportedAbis. " +
                "It is for local debug installs only - pass it with -P rather than putting it " +
                "in gradle.properties."
        }
    }
}

/**
 * The short commit this is being built from, or null when that cannot be known.
 *
 * **For log headers only.** It must never reach `versionName`: the app stamps
 * `via: OpenTagViewer.android:<versionName>` into every bundle it exports, and rule 9 is that
 * nothing patches a version at build time, because two artifacts built from one commit would
 * then disagree about what produced them. A build description is a different question from a
 * product version, and only the first one wants a commit in it.
 *
 * Null rather than a guess when git is absent or this is not a checkout - a source zip off a
 * release tag has no commit to name, and inventing one is worse than saying nothing.
 */
val gitCommit: String? by lazy {
    runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim().ifEmpty { null }
    }.getOrNull()
}

android {
    namespace = "dev.wander.android.opentagviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.wander.android.opentagviewer"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "1.1.0"

        // Null unless a build type sets it - see the debug block. A release is built from a tag
        // and its versionName is exactly right, so there is nothing a commit would add; the
        // field exists in both variants so code reading it compiles in both.
        buildConfigField("String", "BUILD_COMMIT", "null")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // **Do not add `timeout_msec` here.** It works - a hanging test fails at the cap with
        // its own name - but AndroidJUnitRunner pays for it per test, not per hang: with it set
        // to two minutes, FetchFromICloudFlowTest's ten tests took 56.4s against 9.8s without,
        // and the whole suite went from 2m59s to 9m13s. Measured on this machine, both ways.
        //
        // A hang costs one bad run and is fixed by fixing the test; this cost three minutes of
        // every run forever. If a global cap is wanted, it needs to be a JUnit Timeout rule
        // installed by a custom runner, not this argument.

        ndk {
            // **65 MB of a 105 MB debug APK is native libraries, and half of it is for an ABI
            // the target cannot run.** Chaquopy's CPython, cryptography's OpenSSL and Apple's
            // ADI libraries are all here, twice over. An emulator is x86_64 and a phone is
            // arm64, so a local install always carries about 32 MB it will never load.
            //
            // That is only a papercut until a device runs out of room, and then it is an
            // INSTALL_FAILED_INSUFFICIENT_STORAGE with nothing in it about ABIs. An upgrade
            // needs space for the new APK while the old one is still installed, so the real
            // cost is roughly double.
            //
            // `-PotvAbi=x86_64` builds just the one. Opt-in, so CI, releases and anybody who
            // does not know about it get both, unchanged - a default that silently shipped one
            // ABI would produce a release that installs on nothing.
            abiFilters += requestedAbis ?: supportedAbis
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

            // **`-debug` says this is not a release; it does not say which build.**
            // `versionName` is a committed literal, so every commit after 1.0.5 reports 1.0.5
            // perfectly confidently - and `build-debug.yml` publishes a debug APK artifact, so
            // somebody can be running a build whose version string is months stale. The commit
            // is the only thing that identifies such a build, which is exactly the distinction
            // the exporter's `describe_build()` draws between a frozen download and a checkout.
            buildConfigField(
                "String",
                "BUILD_COMMIT",
                gitCommit?.let { "\"$it\"" } ?: "null")

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

        unitTests {
            // **So a class can be JVM-tested when the only Android thing in it is a log line.**
            //
            // Without this, android.util.Log throws "not mocked" and the whole class has to move
            // to the emulator - two orders of magnitude slower, for logging. AdiLibraryFetcher is
            // the case in point: zip parsing, range arithmetic and an ELF check, none of which
            // needs a device, all of which was untested because Log.i sat in the middle of it.
            //
            // It only affects the android.jar stubs the JVM suite compiles against, which throw
            // by default rather than doing anything. A test that genuinely needs Android
            // behaviour still cannot get it here - it gets a zero or a null, which is a wrong
            // answer rather than a slow one, and that test belongs in androidTest.
            isReturnDefaultValues = true
        }

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
            // **A commit, not a branch.** It used to track the branch head, which meant two
            // builds of the same commit of *this* repo could ship different Python - and,
            // worse, that the bridge tests ran against whatever PyPI's `FindMy==0.9.8` was
            // while the app shipped the fork. Those are different libraries: `serial=` on the
            // Anisette providers exists in one and not the other, so the tests could pass on
            // code the app cannot run, and did.
            //
            // Bumping it is now a deliberate act. Move this and the matching line in
            // `app/src/test/python/requirements.txt` together - they are asserted to agree by
            // test_main.py::test_pinned_versions_match_the_app_build, which previously could
            // not see this line at all because it only understood `name==version`.
            //
            // Note for whoever bumps it: 0.10.x adds protobuf, which publishes a native
            // wheel for desktop platforms and a pure-Python `py3-none-any` one as well.
            // There is no Android wheel, so pip falls back to the pure-Python build - which
            // is correct but markedly slower. The messages here are small enough not to care.
            install("git+https://github.com/parawanderer/FindMy.py@4f940158c437b17ae61a8e90af084d658750629c")

            install("NSKeyedUnArchiver==1.5")

            // OPENTAGVIEWER.yml, read and written by opentagviewer_export.bundle - which the app
            // reaches as soon as it touches AccessoryExport, and will need in earnest once it
            // writes bundles of its own. Pinned to the exporter's version in python/pyproject.toml
            // so one repository ships one PyYAML, for the same reason it ships one FindMy.py.
            install("PyYAML==6.0.2")
        }
    }
    productFlavors {}
    sourceSets {
        getByName("main") {
            // The shared bundle-format package, so `import opentagviewer_export` resolves in
            // the APK as well as on desktop. It is the one implementation of the format and of
            // the accessory-identification heuristic; main.py already calls into it.
            //
            // **Whitelisted, not just added.** `../python` also holds `exporter/` (the desktop
            // wizard, which imports tkinter) and `test/` - and a top-level package named `test`
            // on the Python path shadows the standard library's own `test` module. Pointing
            // srcDir at the directory wholesale would put both in the APK.
            //
            // An include filter rather than a directory move, because the filter is verified
            // rather than assumed - PythonPackagingTest imports each of these on a device and
            // fails if `exporter` or a shadowing `test` came along too.
            //
            // **The filter applies to the whole source set, not to the srcDir it follows.**
            // `include("opentagviewer_export/**")` alone therefore drops main.py - the bridge
            // this app cannot run without - out of the APK, silently and with a green build.
            // Hence the first pattern, which keeps app/src/main/python/main.py. `../python`
            // has no top-level .py files for it to catch by accident, and the test is what
            // notices if that stops being true.
            srcDir("../python")
            // **Named files from `exporter/`, not the package.** That directory also holds the
            // tkinter wizard, the questionary CLI and prompt_toolkit prompts, none of which
            // exist on Android - importing the package wholesale would drag them in. These four
            // are stdlib plus FindMy.py, and `exporter/__init__.py` is empty, so importing
            // `exporter.icloud` reaches none of the rest.
            //
            // A list rather than a glob for the same reason: a module added to `exporter/` later
            // should have to be considered before it ships in the APK, not swept in.
            include(
                "*.py",
                "opentagviewer_export/**",
                "exporter/__init__.py",
                "exporter/icloud.py",
                "exporter/device.py",
                "exporter/identity.py",
                // Renders Apple's terms of service into readable text. Its only import beyond
                // the standard library is bs4, which FindMy.py already brings. Shared with the
                // desktop rather than reimplemented because what is displayed is what gets
                // agreed to, and two renderers would eventually show two different documents.
                "exporter/terms.py",
                // Strips personal identifiers out of a log before anybody sends it. Pure stdlib -
                // re, Counter, dataclass - and nothing else in exporter/.
                //
                // Shared for the same reason as terms.py, and more sharply: the wizard's Save
                // logs button already runs these rules, and a second set in Java would mean two
                // answers to "is my Apple ID in this file". The rules are patterns, so they need
                // adding to as new identifiers turn up, and the one that gets forgotten is the
                // copy nobody is looking at.
                "exporter/redact.py",
            )
            // The package's own test suite is not part of the app. It imports pytest, which is
            // not in the APK, so it is dead weight that would fail if anything ever touched it.
            exclude("opentagviewer_export/tests/**")
        }
    }
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
    // RecyclerViewActions, for the two screens that are lists: the history of one tag, and the
    // device list. Espresso's own matchers cannot reach a row that has not been scrolled to,
    // and a test that only ever touches the first visible row is a test of the first row.
    //
    // **protobuf-lite is excluded, and without that this breaks unrelated tests.** contrib
    // pulls com.google.protobuf:protobuf-lite:3.0.1 for its accessibility checks, which this
    // suite does not use. That version predates
    // `GeneratedMessageLite.registerDefaultInstance(Class, GeneratedMessageLite)`, and Cronet
    // calls exactly that while deciding whether to use the HTTP engine - so anything that
    // builds a CronetEngine dies with NoSuchMethodError. In this app that is the Anisette
    // server tester, which SettingsActivity constructs, so simply adding this dependency
    // stopped every Settings test from running - with a failure naming protobuf and Chromium
    // and nothing about the test.
    androidTestImplementation(libs.espresso.contrib) {
        exclude(group = "com.google.protobuf", module = "protobuf-lite")
    }
    androidTestImplementation(libs.android.room.testing)

    annotationProcessor(libs.projectlombok)
    annotationProcessor(libs.android.room.compiler)

    testAnnotationProcessor(libs.projectlombok)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
