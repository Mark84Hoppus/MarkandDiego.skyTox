plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinKsp)
    alias(libs.plugins.googleServices)
}

val skytoxUniversal = providers.gradleProperty("skytoxUniversal").map(String::toBoolean).getOrElse(false)
val skytoxServerEnv = rootProject.file("skytoxserver/.env")
val skytoxPushConfig = if (skytoxServerEnv.exists()) {
    skytoxServerEnv.readLines()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                null
            } else {
                trimmed.substringBefore("=") to trimmed.substringAfter("=")
            }
        }
        .toMap()
} else {
    emptyMap()
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2
    }
}

android {
    namespace = "ltd.evilcorp.atox"
    compileSdk = libs.versions.sdk.target.get().toInt()
    defaultConfig {
        applicationId = "markanddiego.skytox"
        minSdk = libs.versions.sdk.min.get().toInt()
        targetSdk = libs.versions.sdk.target.get().toInt()
        versionCode = 200
        versionName = "0.8.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SKYTOX_PUSH_SERVER_URL", "\"http://100.113.219.109:8787/push\"")
        buildConfigField("String", "SKYTOX_PUSH_API_KEY", "\"${skytoxPushConfig["SKYTOX_PUSH_API_KEY"].orEmpty()}\"")
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ""
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles("proguard-tox4j.pro", getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    signingConfigs {
        getByName("debug") {
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeFile = file("debug.keystore")
            storePassword = "android"
        }
    }
    splits {
        abi {
            isEnable = !skytoxUniversal
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    lint {
        disable += setOf("GoogleAppIndexingWarning", "MissingTranslation")
    }
    packaging {
        // Work around scala-compiler and scala-library (via tox4j) trying to place files in the
        // same place.
        resources.excludes.add("rootdoc.txt")
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val abiVersionCodes = mapOf(
            "armeabi-v7a" to 201,
            "arm64-v8a" to 202,
            "x86" to 203,
            "x86_64" to 204,
        )
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier
            val versionCode = abiVersionCodes[abi]
            if (versionCode != null) {
                output.versionCode.set(versionCode)
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment)

    implementation(libs.google.android.material)
    implementation(platform(libs.google.firebase.bom))
    implementation(libs.google.firebase.messaging)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)

    implementation(libs.androidx.preference)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(libs.google.dagger.core)
    ksp(libs.google.dagger.compiler)

    implementation(libs.nayuki.qrcodegen)

    implementation(libs.square.picasso)

    debugImplementation(libs.square.leakcanary)

    androidTestImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.espresso.core)
    androidTestImplementation(libs.test.espresso.contrib)
    androidTestImplementation(libs.test.junit.ext)
    kspAndroidTest(libs.google.dagger.compiler)

    modules {
        module("com.google.guava:listenablefuture") {
            replacedBy("com.google.guava:guava", "listenablefuture is part of guava")
        }
    }
}
