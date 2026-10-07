pluginManagement {
<<<<<<< HEAD
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "meshchat "

include(":app")
=======
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master") }
        maven { url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master") }
    }
}

rootProject.name = "Bitzlink"
include(":app")
>>>>>>> ac73626a1b48e44cac0eac3a08e705c226446cc4
