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
    }
}

rootProject.name = "Nexora"

include(
    ":app-mobile",
    ":app-tv",
    ":core:common",
    ":core:database",
    ":core:designsystem",
    ":core:logging",
    ":core:model",
    ":core:network",
    ":feature:home",
    ":feature:library",
    ":feature:player",
    ":feature:settings",
    ":feature:sources",
    ":player:api",
    ":player:media3",
    ":source:api",
    ":source:config",
    ":source:plugin-api",
    ":source:runtime",
    ":source:sandbox",
    ":source:testkit",
    ":storage:api",
    ":storage:local",
    ":storage:nfs",
    ":storage:smb",
    ":storage:webdav",
)
