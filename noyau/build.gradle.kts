import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Module « noyau » : calculs en Kotlin pur, sans Android, testés automatiquement.
plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // org.json (lecture et écriture de JSON) : déjà fourni par Android dans le téléphone,
    // donc seulement « pour compiler » ici ; et pour de vrai dans les tests sur ordinateur.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
