// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.google.android.libraries.mapsplatform.secrets-gradle-plugin:secrets-gradle-plugin:2.0.1")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.google.android.libraries.mapsplatform.secrets.gradle.plugin) apply false
    alias(libs.plugins.chaquopy) apply false
}
// ---------------------------------------------------------------------------
// Aggregate test tasks
//
// Tests live in four places across three runners (see TESTING.md). These wrap them
// so there is one command to run everything, rather than four to remember.
// ---------------------------------------------------------------------------

val testVenvDir = layout.buildDirectory.dir("test-venv")

/**
 * Locate a usable Python 3 interpreter.
 *
 * On Windows `python3` is normally a zero-byte Microsoft Store "App Execution Alias"
 * that hangs forever when run non-interactively rather than failing, so candidates are
 * ordered per-platform and alias stubs are rejected by inspecting the file instead of
 * executing it.
 */
fun findPython(): String {
    providers.gradleProperty("pythonExecutable").orNull?.let { return it }

    val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val names = if (isWindows) listOf("python.exe", "python3.exe") else listOf("python3", "python")

    for (name in names) {
        for (dir in (System.getenv("PATH") ?: "").split(File.pathSeparator).filter { it.isNotBlank() }) {
            val candidate = File(dir, name)
            if (!candidate.isFile || !candidate.canExecute()) continue
            if (candidate.length() == 0L) continue
            if (candidate.absolutePath.contains("WindowsApps", ignoreCase = true)) continue
            return candidate.absolutePath
        }
    }
    throw GradleException(
        "No usable Python 3 interpreter found on PATH. Install Python 3 or pass " +
        "-PpythonExecutable=/path/to/python."
    )
}

fun venvBin(name: String): File {
    val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val binDir = if (isWindows) "Scripts" else "bin"
    val exe = if (isWindows) "$name.exe" else name
    return testVenvDir.get().file("$binDir/$exe").asFile
}

val bridgeRequirements = file("app/src/test/python/requirements.txt")
val wizardRequirements = file("python/requirements.txt")

// Created once and reused, so a fresh clone can run the tests with no manual setup.
val setupTestVenv by tasks.registering {
    group = "verification"
    description = "Creates the shared virtualenv used by the pytest suites."

    inputs.file(bridgeRequirements)
    inputs.file(wizardRequirements)
    outputs.dir(testVenvDir)

    doLast {
        val python = findPython()
        val venv = testVenvDir.get().asFile

        if (!venvBin("python").isFile) {
            venv.parentFile.mkdirs()
            providers.exec {
                commandLine(python, "-m", "venv", venv.absolutePath)
            }.result.get().assertNormalExitValue()
        }

        val pip = venvBin("python").absolutePath
        listOf(bridgeRequirements, wizardRequirements).forEach { requirements ->
            logger.lifecycle("Installing ${requirements.name} into the test venv...")
            providers.exec {
                commandLine(pip, "-m", "pip", "install", "-q", "-r", requirements.absolutePath)
            }.result.get().assertNormalExitValue()
        }
        providers.exec {
            commandLine(pip, "-m", "pip", "install", "-q", "pytest")
        }.result.get().assertNormalExitValue()
    }
}

// The venv path is deterministic, so the command can be built up front - the interpreter
// only has to exist by the time the task actually runs, which setupTestVenv guarantees.
val pytestChaquopyBridge by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the pytest suite for app/src/main/python (the Chaquopy bridge)."
    dependsOn(setupTestVenv)
    commandLine(venvBin("python").absolutePath, "-m", "pytest", "app/src/test/python", "-v")
}

val pytestDesktopWizard by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the pytest suite for the macOS export wizard under python/."
    dependsOn(setupTestVenv)
    workingDir = file("python")
    commandLine(venvBin("python").absolutePath, "-m", "pytest", "./test", "-v")
}

val testAll by tasks.registering {
    group = "verification"
    description = "Runs every test suite that does not need a device or emulator."
    dependsOn(":app:testDebugUnitTest", pytestChaquopyBridge, pytestDesktopWizard)

    doLast {
        logger.lifecycle("")
        logger.lifecycle("All device-free suites passed.")
        logger.lifecycle("Instrumented tests were NOT run - use ./gradlew testAllOnDevice with an emulator booted.")
    }
}

val testAllOnDevice by tasks.registering {
    group = "verification"
    description = "Runs every test suite, including instrumented tests (needs a device or emulator)."
    dependsOn(testAll, ":app:connectedDebugAndroidTest")

    doLast {
        logger.lifecycle("")
        logger.lifecycle("All suites passed, including instrumented tests.")
    }
}
