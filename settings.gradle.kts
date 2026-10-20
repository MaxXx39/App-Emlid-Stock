// Liste des modules du projet :
// - noyau : les calculs (lecture NMEA, vérifications), en Kotlin pur, testables sans téléphone.
// - app : l'application Android (écrans, Bluetooth, position).
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

rootProject.name = "EmlidStock"
include(":noyau")
include(":app")
