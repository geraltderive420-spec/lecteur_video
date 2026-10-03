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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LecteurMedia"

// Core modules
include(":core:model")
include(":core:common")
include(":core:designsystem")
include(":core:database")
include(":core:network")
include(":core:data")
include(":core:player")

// Feature modules
include(":feature:home")
include(":feature:library")
include(":feature:details")
include(":feature:player")
include(":feature:scanner")
include(":feature:settings")
include(":feature:cast")

// App targets
include(":app")
include(":app-tv")
