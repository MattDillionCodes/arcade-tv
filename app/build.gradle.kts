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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("com.google.zxing:core:3.5.3")
}
