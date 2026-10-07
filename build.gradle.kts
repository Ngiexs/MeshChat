<<<<<<< HEAD
// AGP 9 compiles Kotlin itself and refuses the `org.jetbrains.kotlin.android` plugin.
// It takes the compiler from the kotlin-gradle-plugin jar on this classpath, so this
// is where the Kotlin version is pinned. Code On The Go injects the on-device Maven
// repo here so it resolves offline.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
=======
plugins {
    id("com.android.application") version "8.10.0" apply false
>>>>>>> ac73626a1b48e44cac0eac3a08e705c226446cc4
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}