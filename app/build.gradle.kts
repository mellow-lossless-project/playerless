plugins {
    id("com.android.application")
}

android {
    namespace = "com.playerless"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.playerless"
        minSdk = 26
        targetSdk = 29
        versionCode = 7
        versionName = "0.7"
    }

    // Media3 ships strings for ~80 locales; keep only the ones we use.
    androidResources {
        localeFilters += listOf("en", "it")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded personal build, the debug key is enough.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += listOf("kotlin/**", "DebugProbesKt.bin")
    }

    dependenciesInfo {
        includeInApk = false
    }

    lint {
        // targetSdk 29 is deliberate (matches the A750F) and Play rules don't apply.
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-session:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
