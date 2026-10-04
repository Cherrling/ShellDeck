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

rootProject.name = "ShellDeck"
include(":app")
include(":terminal-emulator", ":terminal-view")
project(":terminal-emulator").projectDir = file("third-party/termux/terminal-emulator")
project(":terminal-view").projectDir = file("third-party/termux/terminal-view")
