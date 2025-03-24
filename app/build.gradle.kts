plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.android.libraries.mapsplatform.secrets.gradle.plugin)
    alias(libs.plugins.gradle.lombok)
    alias(libs.plugins.chaquopy)
}

secrets {
    // To add your Maps API key to this project:
    // 1. If the secrets.properties file does not exist, create it in the same folder as the local.properties file.
    // 2. Add this line, where YOUR_API_KEY is your API key:
    //        MAPS_API_KEY=YOUR_API_KEY
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
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
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
}

lombok {
    version = libs.versions.lombokVersion.get()
}

chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            // SEE: https://chaquo.com/chaquopy/doc/current/android.html#android-requirements
            install("FindMy==0.7.6")
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
    implementation(libs.google.play.location)
    implementation(libs.google.places)
    implementation(libs.androidx.emoji)
    implementation(libs.androidx.emoji.views)
    implementation(libs.androidx.emoji.views.helper)
    implementation(libs.androidx.emoji.picker)

    testImplementation(libs.junit)
    testImplementation(libs.android.room.testing)
    testCompileOnly(libs.projectlombok)

    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    annotationProcessor(libs.projectlombok)
    annotationProcessor(libs.android.room.compiler)

    testAnnotationProcessor(libs.projectlombok)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
}