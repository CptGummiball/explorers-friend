import net.fabricmc.loom.api.LoomGradleExtensionAPI
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.kotlin.dsl.*

/**
 * Shared platform configuration for both eras.
 * yarnEra = obfuscated Minecraft (<= 1.21.11): legacy loom plugin, yarn mappings,
 * mod* configurations and a remapJar output.
 * !yarnEra = unobfuscated Minecraft (>= 26.1): no-remap loom plugin, plain
 * implementation dependencies, the jar task output IS the artifact.
 */
fun Project.configureEfPlatform(yarnEra: Boolean) {
    val mc = property("mc") as String
    val fabricApi = property("fabricApi") as String
    val loaderVersion = property("loaderVersion") as String
    val javaVersion = (findProperty("javaVersion") as String? ?: "21").toInt()
    val artifactMc = (findProperty("artifactMc") as String? ?: mc)

    extensions.configure<org.gradle.api.plugins.BasePluginExtension>("base") {
        archivesName.set("explorersfriend-fabric-" + artifactMc)
    }

    repositories.mavenCentral()
    repositories.maven("https://maven.fabricmc.net/") { name = "FabricMC" }
    repositories.maven("https://maven.ftb.dev/releases") {
        name = "FTB"
        content { includeGroup("dev.ftb.mods") }
    }
    repositories.maven("https://maven.architectury.dev/") {
        name = "Architectury"
        content { includeGroup("dev.architectury") }
    }
    repositories.maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
        content { includeGroup("maven.modrinth") }
    }

    dependencies.add("minecraft", "com.mojang:minecraft:" + mc)
    if (yarnEra) {
        val yarn = property("yarn") as String
        dependencies.add("mappings", "net.fabricmc:yarn:" + yarn + ":v2")
        dependencies.add("modImplementation", "net.fabricmc:fabric-loader:" + loaderVersion)
        dependencies.add("modImplementation", "net.fabricmc.fabric-api:fabric-api:" + fabricApi)
    } else {
        dependencies.add("implementation", "net.fabricmc:fabric-loader:" + loaderVersion)
        dependencies.add("implementation", "net.fabricmc.fabric-api:fabric-api:" + fabricApi)
    }
    dependencies.add("implementation", dependencies.project(mapOf("path" to ":common")))

    val accessWidener = file("src/main/resources/explorersfriend.accesswidener")
    if (accessWidener.exists()) {
        extensions.configure<LoomGradleExtensionAPI>("loom") {
            accessWidenerPath.set(accessWidener)
        }
    }

    extensions.configure<JavaPluginExtension>("java") {
        toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
    tasks.withType(JavaCompile::class.java).configureEach {
        options.encoding = "UTF-8"
        options.release.set(javaVersion)
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }

    evaluationDependsOn(":common")
    tasks.named("jar", Jar::class.java) {
        val commonMain = project(":common").extensions
            .getByType(SourceSetContainer::class.java).getByName("main")
        from(commonMain.output)
        from(rootProject.file("LICENSE")) { rename { "LICENSE_explorersfriend" } }
        from(rootProject.file("THIRD_PARTY_NOTICES.md")) { rename { "THIRD_PARTY_NOTICES_explorersfriend.md" } }
        duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    }
    tasks.named("processResources", ProcessResources::class.java) {
        inputs.property("version", project.version)
        filesMatching("fabric.mod.json") {
            expand(mapOf("version" to project.version))
        }
    }
}
