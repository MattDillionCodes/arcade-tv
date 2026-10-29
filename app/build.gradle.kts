plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.matthew.arcade"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.matthew.arcade"
        minSdk = 28 // Android TV 9+, as promised on the showcase page
        targetSdk = 35
        versionCode = 100
        versionName = "1.0.0"
    }

    // Public releases are signed with your own key, passed in by CI (see .github/workflows/release.yml).
    // Without it (local ./install.sh builds) release falls back to the debug key, so a dev install
    // updates in place on a test TV without losing its settings.
    val keystore = System.getenv("ARCADE_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = System.getenv("ARCADE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ARCADE_KEY_ALIAS")
            keyPassword = System.getenv("ARCADE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // QR encoder for the wallpaper upload code (pure Java, R8 strips what isn't used).
    implementation("com.google.zxing:core:3.5.4")
}
