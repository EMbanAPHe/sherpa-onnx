plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.k2fsa.sherpa.onnx.tts.engine"
    compileSdk = 34

    defaultConfig {
        val baseId = "com.k2fsa.sherpa.onnx.tts.engine"
        val suffix = (project.findProperty("APP_ID_SUFFIX") as String?) ?: ""
        applicationId = baseId + suffix

        minSdk    = 21
        targetSdk = 34
        versionCode = 20260327
        versionName = "1.12.15-fork"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        fun prop(key: String): String = (project.findProperty(key) as String?) ?: ""
        buildConfigField("String",  "TTSENGINE_MODEL_DIR",  "\"${prop("TTSENGINE_MODEL_DIR")}\"")
        buildConfigField("String",  "TTSENGINE_MODEL_NAME", "\"${prop("TTSENGINE_MODEL_NAME")}\"")
        buildConfigField("String",  "TTSENGINE_VOICES",     "\"${prop("TTSENGINE_VOICES")}\"")
        buildConfigField("String",  "TTSENGINE_DATA_DIR",   "\"${prop("TTSENGINE_DATA_DIR")}\"")
        buildConfigField("String",  "TTSENGINE_LANG",       "\"${prop("TTSENGINE_LANG")}\"")
        buildConfigField("boolean", "TTSENGINE_IS_KITTEN",  "${prop("TTSENGINE_IS_KITTEN") == "true"}")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures {
        compose     = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    // The sherpa-onnx AAR is downloaded from GitHub releases by the CI workflow
    // into app/libs/ before assembleDebug runs. This avoids any JitPack dependency.
    // For local dev builds, run: wget -P app/libs https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.15/sherpa-onnx-1.12.15.aar
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

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
