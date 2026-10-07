plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.dynamicisland.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dynamicisland.app"
        minSdk = 31          // API 31 required for RenderEffect & BlurMaskFilter APIs
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // ── Aggressive size minimisation ─────────────────────────────────
            isMinifyEnabled = true        // R8 full-mode code shrinking + obfuscation
            isShrinkResources = true      // Remove unused resources
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".debug"
        }
    }

    // Enable R8 full mode for maximum shrinking
    buildFeatures {
        // viewBinding deliberately omitted — MainActivity uses findViewById to keep the
        // build pipeline simple and avoid the dataBinding instrumentation tasks.
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Enable kotlin optimisations
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xjvm-default=all"
        )
    }

    // Split APKs per ABI to further reduce download size
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "kotlin/**",
                "DebugProbesKt.bin"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    // Spring physics – the ONLY third-party animation dependency (tiny, native AndroidX)
    implementation(libs.androidx.dynamicanimation)
    // LifecycleService for the foreground service
    implementation(libs.androidx.lifecycle.service)
}
