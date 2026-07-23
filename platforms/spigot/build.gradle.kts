// Spigot/Paper backend: plain Java against the version-stable Bukkit API - no
// loom, no mappings, no NMS. One artifact covers every supported MC version.
plugins {
    `java-library`
}

base.archivesName.set("explorersfriend-spigot-paper")

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
        name = "SpigotMC"
    }
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("org.slf4j:slf4j-api:2.0.9")
    implementation(project(":common"))
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

evaluationDependsOn(":common")
tasks.named<Jar>("jar") {
    val commonMain = project(":common").extensions
        .getByType(SourceSetContainer::class.java).getByName("main")
    from(commonMain.output)
    from(rootProject.file("LICENSE")) { rename { "LICENSE_explorersfriend" } }
    from(rootProject.file("THIRD_PARTY_NOTICES.md")) { rename { "THIRD_PARTY_NOTICES_explorersfriend.md" } }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
tasks.named<ProcessResources>("processResources") {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand(mapOf("version" to project.version))
    }
}
