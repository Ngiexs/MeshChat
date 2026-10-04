plugins {
    id("com.android.application")
}

android {
    namespace = "com.bitzlink"
    compileSdk = 36

    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    defaultConfig {
        applicationId = "com.bitzlink"
        minSdk = 21
        targetSdk = 35
        versionCode = 2
        versionName = "0.4.0-beta1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.core:core:1.13.1")
    // ... any other existing lines ...
}