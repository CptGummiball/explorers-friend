// Convention for every platform module. Per-module facts come from the module's
// gradle.properties: mc, fabricApi, loaderVersion, javaVersion, artifactMc and —
// only in the obfuscated/yarn era — yarn. 26.x modules simply omit "yarn": Minecraft
// is unobfuscated there and loom needs no mapping dependency (same plugin id, the
// remap pipeline collapses to packaging).
plugins {
    id("fabric-loom")
    `java-library`
}

val mc: String by project
val fabricApi: String by project
val loaderVersion: String by project
val javaVersion = (findProperty("javaVersion") as String? ?: "21").toInt()
val artifactMc = (findProperty("artifactMc") as String? ?: mc)
val yarn = findProperty("yarn") as String?

base {
    archivesName = "explorersfriend-fabric-$artifactMc"
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

dependencies {
    "minecraft"("com.mojang:minecraft:$mc")
    if (yarn != null) {
        "mappings"("net.fabricmc:yarn:$yarn:v2")
    }
    "modImplementation"("net.fabricmc:fabric-loader:$loaderVersion")
    "modImplementation"("net.fabricmc.fabric-api:fabric-api:$fabricApi")
    "implementation"(project(":common"))
}

extensions.configure<net.fabricmc.loom.api.LoomGradleExtensionAPI>("loom") {
    val accessWidener = file("src/main/resources/explorersfriend.accesswidener")
    if (accessWidener.exists()) {
        accessWidenerPath.set(accessWidener)
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = javaVersion
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

// Bundle the Minecraft-independent core (classes + web assets) into this platform's
// jar — one self-contained artifact per target, no jar-in-jar indirection.
evaluationDependsOn(":common")
tasks.named<Jar>("jar") {
    val commonMain = project(":common").extensions
        .getByType<SourceSetContainer>()["main"]
    from(commonMain.output)
    from(rootProject.file("LICENSE")) { rename { "LICENSE_explorersfriend" } }
    from(rootProject.file("THIRD_PARTY_NOTICES.md")) { rename { "THIRD_PARTY_NOTICES_explorersfriend.md" } }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named<ProcessResources>("processResources") {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
