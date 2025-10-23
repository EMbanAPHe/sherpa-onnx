// android/SherpaOnnxTtsEngine/app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // keep your existing namespace
    namespace = "com.k2fsa.sherpa.onnx.tts.engine"
    compileSdk = 34

    // match the NDK installed in your GitHub Actions workflow
    ndkVersion = "26.1.10909125"

    defaultConfig {
        // keep your existing app id
        applicationId = "com.k2fsa.sherpa.onnx.tts.engine"
        minSdk = 21
        targetSdk = 34

        // keep your current versioning
        versionCode = 20250918
        versionName = "1.12.14"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // build only arm64 for smaller/faster builds
        ndk { abiFilters += listOf("arm64-v8a") }

        // this was in your file; keep it
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug { /* defaults */ }
    }

    // Java/Kotlin targets compatible with your codebase + Compose
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
        // helpful for default interface methods on older Android
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    // >>> Compose is enabled here <<<
    buildFeatures {
        compose = true
    }
    composeOptions {
        // Compose Compiler 1.5.1 pairs with Kotlin 1.9.0
        kotlinCompilerExtensionVersion = "1.5.1"
    }

    // same exclude you had; avoids license metadata merge issues
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // keep your existing libs (versions as in your screenshot)
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM to align Compose artifacts
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")

    // tests
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // compose tooling/debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
