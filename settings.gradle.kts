pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://repo.boox.com/repository/maven-public/") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Writer"
include(":proto")
include(":app")
include(":tools:inkup-viewer")

// Vendored inksdk library (low-latency stylus controller). The full repo is
// pulled in under third_party/inksdk via git subtree; we only consume the
// :inkcontroller module from it (the :demo module stays unbuilt).
include(":inksdk")
project(":inksdk").projectDir = file("third_party/inksdk/inkcontroller")
