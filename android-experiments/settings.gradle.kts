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

rootProject.name = "locshield-experiments"
include(
    ":apps:location-client",
    ":apps:gms-client",
    ":apps:producer",
    ":apps:passive-consumer",
    ":apps:geofence-client",
)
