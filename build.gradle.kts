import java.security.MessageDigest
import java.util.zip.ZipFile

// Root: aggregation, dist packaging, artifact verification. No Java code here.
plugins {
    base
}

val modVersion = property("mod_version") as String

allprojects {
    group = "net.explorersfriend"
    version = modVersion
}

val platformProjects = subprojects.filter { it.path.startsWith(":platforms:") }
val distDir = layout.projectDirectory.dir("dist")

tasks.register("buildAllVersions") {
    group = "explorersfriend"
    description = "Builds every platform artifact plus the common core."
    dependsOn(":common:build")
    platformProjects.forEach { dependsOn("${it.path}:build") }
}

tasks.register("testAllVersions") {
    group = "explorersfriend"
    description = "Runs every test suite (core unit tests + per-platform tests where present)."
    dependsOn(":common:test")
    platformProjects.forEach { dependsOn("${it.path}:test") }
}

// GameTests are not used (documented); alias so the documented task name works.
tasks.register("gameTestAllVersions") {
    group = "explorersfriend"
    description = "Alias of testAllVersions (no Minecraft GameTest harness in this project)."
    dependsOn("testAllVersions")
}

tasks.register("packageAllVersions") {
    group = "explorersfriend"
    description = "Collects all release jars into dist/ with SHA-256 sums and a release manifest."
    dependsOn("buildAllVersions")
    doLast {
        val dist = distDir.asFile
        dist.mkdirs()
        dist.listFiles()?.filter { it.name.endsWith(".jar") || it.name.endsWith(".txt") }?.forEach { it.delete() }
        val checksums = StringBuilder()
        val artifacts = mutableListOf<Map<String, Any>>()
        val testResults = file("dist/test-results.json").takeIf { it.exists() }
            ?.let { groovy.json.JsonSlurper().parse(it) as Map<*, *> } ?: emptyMap<Any, Any>()
        platformProjects.forEach { p ->
            val artifactTask = p.tasks.findByName("remapJar") ?: p.tasks.getByName("jar")
            val remapJar = artifactTask.outputs.files.files.first { it.name.endsWith(".jar") && !it.name.contains("-sources") }
            val target = dist.resolve(remapJar.name)
            remapJar.copyTo(target, overwrite = true)
            val digest = MessageDigest.getInstance("SHA-256")
            target.inputStream().use { input ->
                val buffer = ByteArray(65536)
                var read = input.read(buffer)
                while (read >= 0) {
                    digest.update(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            checksums.append(sha).append("  ").append(target.name).append('\n')
            @Suppress("UNCHECKED_CAST")
            val moduleMeta = (testResults[p.name] as? Map<String, Any>) ?: emptyMap()
            artifacts.add(
                mapOf(
                    "file" to target.name,
                    "module" to p.name,
                    "minecraft" to (p.findProperty("supportedMc") as String? ?: p.findProperty("mc") as String)
                        .split(",").map { it.trim() },
                    "java" to ((p.findProperty("javaVersion") as String? ?: "21").toInt()),
                    "fabricLoader" to (p.findProperty("loaderVersion") as String),
                    "fabricApi" to (p.findProperty("fabricApi") as String),
                    "sha256" to sha,
                    "tested" to (moduleMeta["smoke"] == "passed")
                )
            )
        }
        dist.resolve("checksums-sha256.txt").writeText(checksums.toString())
        val gitCommit = try {
            val process = ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(rootDir).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }
        } catch (e: Exception) {
            "unknown"
        }
        val manifest = mapOf(
            "modVersion" to modVersion,
            "gitCommit" to gitCommit,
            "builtAt" to java.time.OffsetDateTime.now().toString(),
            "artifacts" to artifacts
        )
        dist.resolve("release-manifest.json")
            .writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(manifest)))
        val compatibility = mapOf(
            "modVersion" to modVersion,
            "versions" to artifacts.flatMap { a ->
                @Suppress("UNCHECKED_CAST")
                (a["minecraft"] as List<String>).map { mcVer ->
                    mapOf(
                        "minecraft" to mcVer,
                        "artifact" to a["file"],
                        "java" to a["java"],
                        "fabricLoaderMin" to a["fabricLoader"],
                        "tested" to a["tested"]
                    )
                }
            }
        )
        dist.resolve("compatibility.json")
            .writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(compatibility)))
        println("dist/: ${artifacts.size} artifact(s) packaged")
    }
}

tasks.register("verifyAllArtifacts") {
    group = "explorersfriend"
    description = "Structural checks on every dist jar: fabric.mod.json version range, no test classes, core present."
    dependsOn("packageAllVersions")
    doLast {
        val jars = distDir.asFile.listFiles { f -> f.name.endsWith(".jar") } ?: emptyArray()
        require(jars.isNotEmpty()) { "no jars in dist/" }
        jars.forEach { jarFile ->
            ZipFile(jarFile).use { zip ->
                val fmj = zip.getEntry("fabric.mod.json")
                    ?: error("${jarFile.name}: fabric.mod.json missing")
                val json = groovy.json.JsonSlurper()
                    .parse(zip.getInputStream(fmj).readBytes()) as Map<*, *>
                val depends = json["depends"] as Map<*, *>
                require((depends["minecraft"] as String).isNotBlank()) { "${jarFile.name}: empty minecraft range" }
                require(json["version"] == modVersion) { "${jarFile.name}: wrong version ${json["version"]}" }
                require(zip.getEntry("net/explorersfriend/web/MapHttpServer.class") != null) {
                    "${jarFile.name}: common core classes missing"
                }
                require(zip.getEntry("web/index.html") != null) { "${jarFile.name}: web UI missing" }
                val testClasses = zip.entries().asSequence()
                    .filter { it.name.endsWith("Test.class") }.count()
                require(testClasses == 0) { "${jarFile.name}: contains $testClasses test classes" }
            }
            println("verified: ${jarFile.name}")
        }
    }
}
