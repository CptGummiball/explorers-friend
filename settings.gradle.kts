pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "explorersfriend"

include(":common")
// every directory under platforms/ with a build script is a platform module
file("platforms").listFiles()
    ?.filter { it.isDirectory && java.io.File(it, "build.gradle.kts").exists() }
    ?.sortedBy { it.name }
    ?.forEach { include(":platforms:${it.name}") }
