pluginManagement {
    repositories {
        google()
        // repo1 is Maven Central's canonical host; listed first because repo.maven.apache.org
        // rate-limits some CI/proxy networks (HTTP 429).
        maven("https://repo1.maven.org/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://repo1.maven.org/maven2/")
        mavenCentral()
    }
}
rootProject.name = "FloorPlanScanner"
include(":core", ":app")
