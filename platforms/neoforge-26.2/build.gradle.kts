// NeoForge backend for Minecraft 1.21.1 (mojmap at runtime, ModDevGradle).
plugins {
    `java-library`
    id("net.neoforged.moddev") version "2.0.142"
}

base.archivesName.set("explorersfriend-neoforge-26.2")

neoForge {
    version = "26.2.0.32-beta"
    accessTransformers.from("src/main/resources/META-INF/accesstransformer.cfg")
}

repositories {
    mavenCentral()
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
        content { includeGroup("maven.modrinth") }
    }
}

dependencies {
    implementation(project(":common"))
    compileOnly("maven.modrinth:waystones:26.2.0.5+neoforge-26.2") { isTransitive = false }
    compileOnly("maven.modrinth:open-parties-and-claims:neoforge-26.2-0.28.1") { isTransitive = false }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
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
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(mapOf("version" to project.version))
    }
}
