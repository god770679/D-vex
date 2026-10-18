pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // Project-local mirror for artifacts not published to Maven Central:
    // com.k2fsa:sherpa-onnx-android:1.13.8 (Apache-2.0, GitHub release v1.13.8).
    maven {
      url = uri(rootDir.resolve("repo"))
    }
  }
}

rootProject.name = "D-VEX"

include(":app")
