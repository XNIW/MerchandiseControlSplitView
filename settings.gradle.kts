pluginManagement {
    repositories {
        google() // Rimuovi il blocco content per permettere la risoluzione di tutti i plugin di Google, incluso KSP
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

rootProject.name = "MerchandiseControlSplitView"
include(":app")