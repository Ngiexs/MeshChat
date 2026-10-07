<<<<<<< HEAD
import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
}

val keystorePropsFile = rootProject.file("release.properties")
val keystoreProps = Properties()

if (keystorePropsFile.exists()) {
    keystoreProps.load(FileInputStream(keystorePropsFile))
}

val hasValidSigningProps = keystorePropsFile.exists().also { exists ->
    if (exists) {
        FileInputStream(keystorePropsFile).use { keystoreProps.load(it) }
    }
}.let {
    listOf("storeFile", "storePassword",
            "keyAlias", "keyPassword").all { key ->
        keystoreProps[key] != null
    }
}


=======
plugins {
    id("com.android.application")
}

>>>>>>> ac73626a1b48e44cac0eac3a08e705c226446cc4
android {
    namespace = "com.bitzlink"
    compileSdk = 36

<<<<<<< HEAD
    // disable linter
    lint {
        checkReleaseBuilds = false
    }

    signingConfigs {
        if (hasValidSigningProps) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
=======
    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
>>>>>>> ac73626a1b48e44cac0eac3a08e705c226446cc4
        }
    }

    defaultConfig {
        applicationId = "com.bitzlink"
<<<<<<< HEAD
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.3_hotFix-beta1"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
=======
        minSdk = 21
        targetSdk = 35
        versionCode = 3
        versionName = "0.4.1-beta1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
>>>>>>> ac73626a1b48e44cac0eac3a08e705c226446cc4
    }

    buildTypes {
        release {
<<<<<<< HEAD
            if (hasValidSigningProps) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        // The key line: extract native libs to the filesystem so
        // MeshChat can spawn libtor.so as a real process.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
            resources.excludes.add("META-INF/kotlinx_coroutines_core.version")

            resources.pickFirsts.add("nonJvmMain/default/linkdata/package_androidx/0_androidx.knm")
            resources.pickFirsts.add("nonJvmMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("nonJvmMain/default/linkdata/module")

            resources.pickFirsts.add("nativeMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("nativeMain/default/linkdata/module")

            resources.pickFirsts.add("commonMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("commonMain/default/linkdata/module")
            resources.pickFirsts.add("commonMain/default/linkdata/package_androidx/0_androidx.knm")

            resources.pickFirsts.add("META-INF/kotlin-project-structure-metadata.json")

            resources.merges.add("commonMain/default/manifest")
            resources.merges.add("nonJvmMain/default/manifest")
            resources.merges.add("nativeMain/default/manifest")
        }
    }

    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.3.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.3.21")

            force("androidx.collection:collection:1.4.2")
            force("androidx.annotation:annotation:1.8.1")
            force("androidx.core:core-ktx:1.8.0")
            force("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")
            force("androidx.collection:collection-ktx:1.4.2")
=======
            isMinifyEnabled = false
>>>>>>> ac73626a1b48e44cac0eac3a08e705c226446cc4
        }
    }
}

<<<<<<< HEAD
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
    }
}


dependencies {
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.2")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.android.material)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.interpolator)
=======
dependencies {
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.core:core:1.13.1")
    // ... any other existing lines ...
>>>>>>> ac73626a1b48e44cac0eac3a08e705c226446cc4
}