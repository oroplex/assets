pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central first: Central's own hosts (repo1 / repo.maven.apache.org)
        // rate-limit some CI/proxy networks (HTTP 429), which Gradle treats as a hard failure.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        maven("https://repo1.maven.org/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        maven("https://repo1.maven.org/maven2/")
        mavenCentral()
    }
}
rootProject.name = "FloorPlanScanner"
include(":core", ":app")
