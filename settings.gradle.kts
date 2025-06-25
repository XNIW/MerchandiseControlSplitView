// In settings.gradle.kts (nella cartella principale del progetto)

// CORREZIONE: il blocco pluginManagement DEVE essere il primo in assoluto.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal() // Necessario per trovare il plugin sottostante
    }
}

// Il plugin per il toolchain viene dichiarato DOPO pluginManagement.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Aggiungi questa riga per nascondere gli avvisi "Incubating"
@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MerchandiseControlSplitView"
include(":app")