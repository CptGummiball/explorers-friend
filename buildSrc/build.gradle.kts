plugins {
    `kotlin-dsl`
}

repositories {
    maven("https://maven.fabricmc.net/") { name = "FabricMC" }
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Makes the loom plugin applicable from the convention script below.
    implementation("fabric-loom:fabric-loom.gradle.plugin:1.17.16")
}
