plugins {
    id("com.android.application") version "8.10.0" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}