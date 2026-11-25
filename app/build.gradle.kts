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
        versionCode = 19
        versionName = "1.6.3"
    }

    // Media3 ships strings for ~80 locales; keep only the app's own, the ones in Lang.CHOICES.
    androidResources {
        localeFilters += listOf("en", "it", "es", "fr", "de", "pt")
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
        // targetSdk 29 is deliberate (matches the A750F) and Play rules don't apply. JioSaavn's
        // links come DES/ECB-encrypted: their format, not a choice made here.
        checkReleaseBuilds = false
        disable += listOf("ExpiredTargetSdkVersion", "GetInstance")
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-session:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
