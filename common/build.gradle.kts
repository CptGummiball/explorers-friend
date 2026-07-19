// The Minecraft-independent core: no net.minecraft imports, no mappings, no loom.
// Compiled at Java 21 bytecode so every platform (Java 21 and Java 25 era) can use it.
plugins {
    `java-library`
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "FabricMC" }
}

dependencies {
    // Provided by Minecraft/Fabric at runtime — never bundled.
    compileOnly("com.google.code.gson:gson:${libs.versions.gson.get()}")
    compileOnly("org.slf4j:slf4j-api:${libs.versions.slf4j.get()}")
    // Fabric Loader's API is Minecraft-version-independent (JAR inventory only).
    compileOnly("net.fabricmc:fabric-loader:${libs.versions.loaderYarnEra.get()}")

    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.google.code.gson:gson:${libs.versions.gson.get()}")
    testImplementation("org.slf4j:slf4j-api:${libs.versions.slf4j.get()}")
    testRuntimeOnly("org.slf4j:slf4j-simple:${libs.versions.slf4j.get()}")
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

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed") }
}
