plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.androidllm"
    compileSdk = 35
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.example.androidllm"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // ARM64 covers every modern phone (incl. OnePlus 13 / Snapdragon 8 Elite).
        // x86_64 is included so the app also runs on the standard Android emulator.
        // Override for a faster single-ABI build, e.g. -PtargetAbi=x86_64
        val targetAbi = (project.findProperty("targetAbi") as String?)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf("arm64-v8a", "x86_64")
        ndk {
            abiFilters += targetAbi
        }

        externalNativeBuild {
            cmake {
                // Keep the native build lean: no curl, no llamafile, release opt.
                arguments += "-DLLAMA_CURL=OFF"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DGGML_LLAMAFILE=OFF"
                arguments += "-DCMAKE_BUILD_TYPE=Release"
            }
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

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Local chat persistence + search.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
