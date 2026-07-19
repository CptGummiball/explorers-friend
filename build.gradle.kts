plugins {
    id("fabric-loom") version "1.17.16"
    `java-library`
}

val modVersion = property("mod_version") as String
val minecraftVersion = property("minecraft_version") as String
val yarnMappings = property("yarn_mappings") as String
val loaderVersion = property("loader_version") as String
val fabricApiVersion = property("fabric_api_version") as String

version = modVersion
group = property("maven_group") as String

base {
    archivesName = "explorersfriend"
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "FabricMC" }
    maven("https://maven.ftb.dev/releases") {
        name = "FTB"
        content { includeGroup("dev.ftb.mods") }
    }
    maven("https://maven.architectury.dev/") {
        name = "Architectury"
        content { includeGroup("dev.architectury") }
    }
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
        content { includeGroup("maven.modrinth") }
    }
}

loom {
    accessWidenerPath = file("src/main/resources/explorersfriend.accesswidener")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // Optional claim-system integrations: compile-only against official APIs.
    // Nothing is bundled; adapters are only activated when the mod is present at runtime.
    modCompileOnly("dev.ftb.mods:ftb-chunks-fabric:2101.1.20") { isTransitive = false }
    modCompileOnly("dev.ftb.mods:ftb-teams-fabric:2101.1.10") { isTransitive = false }
    modCompileOnly("dev.ftb.mods:ftb-library-fabric:2101.1.33") { isTransitive = false }
    modCompileOnly("dev.architectury:architectury-fabric:13.0.6") { isTransitive = false }
    modCompileOnly("maven.modrinth:open-parties-and-claims:cNfQARzn") { isTransitive = false }

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.processResources {
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.jar {
    from("LICENSE") { rename { "LICENSE_explorersfriend" } }
    from("THIRD_PARTY_NOTICES.md") { rename { "THIRD_PARTY_NOTICES_explorersfriend.md" } }
}
