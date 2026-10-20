import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Module « app » : l'application Android.
plugins {
    id("com.android.application")
    kotlin("android")
}

// Numéro de version : le numéro de fabrication GitHub (1 en local).
// Il augmente à chaque fois, ce qui permet d'installer une mise à jour par-dessus l'ancienne.
val numeroVersion = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "ch.emlidstock"
    compileSdk = 35

    defaultConfig {
        applicationId = "ch.emlidstock"
        minSdk = 26
        targetSdk = 35
        versionCode = numeroVersion
        versionName = "0.2.$numeroVersion"
    }

    // Clé de signature fixe, gardée dans le dépôt : toutes les versions sont signées pareil,
    // donc Android accepte de mettre à jour l'appli sans la désinstaller.
    signingConfigs {
        getByName("debug") {
            storeFile = file("cle-signature.jks")
            storePassword = "emlidstock"
            keyAlias = "emlidstock"
            keyPassword = "emlidstock"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":noyau"))
}
