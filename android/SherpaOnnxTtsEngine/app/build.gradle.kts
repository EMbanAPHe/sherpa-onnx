plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Base application ID for the TTS engine
val baseAppId = "com.k2fsa.sherpa.onnx.tts.engine"

// Optional suffix for side-by-side variants, e.g. ".kokoro_en_v019"
// Comes from the workflow env: APP_ID_SUFFIX
val appIdSuffix = System.getenv("APP_ID_SUFFIX") ?: ""

// Final applicationId used for this build
val finalAppId =
    if (appIdSuffix.isBlank()) baseAppId else baseAppId + appIdSuffix

android {
    namespace = "com.k2fsa.sherpa.onnx.tts.engine"
    compileSdk = 34

    defaultConfig {
            // Base ID for all variants
            val baseAppId = "com.k2fsa.sherpa.onnx.tts.engine"

            // Optional suffix passed from Gradle property (e.g. -PAPP_ID_SUFFIX=".kokoro_en_v019")
            val appIdSuffix = (project.findProperty("APP_ID_SUFFIX") as String?) ?: ""

            applicationId = baseAppId + appIdSuffix

            minSdk = 21
            targetSdk = 34
            versionCode = 20251022
            versionName = "1.12.15"

            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            vectorDrawables {
                useSupportLibrary = true
            }
        }

        ndk {
            // Build only for modern ARM64, which your Pixel 8 Pro uses
            abiFilters += listOf("arm64-v8a")
        }

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {

    implementation("com.github.k2-fsa:sherpa-onnx:v1.12.15")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
