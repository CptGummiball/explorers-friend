// Unobfuscated era (Minecraft 26.x): no yarn property -> loom runs without a mapping
// dependency; sources use official Mojang names. Java 25 toolchain.
plugins {
    id("explorersfriend.platform-noremap")
}

// FTB Chunks has no build for this Minecraft generation; OPAC + JSON import are active.
sourceSets["main"].java.exclude("**/claims/provider/FtbChunksClaimProvider.java")

dependencies {
    compileOnly("eu.pb4:common-protection-api:2.0.0") { isTransitive = false }
    compileOnly("maven.modrinth:waystones:26.2.0.5+fabric-26.2") { isTransitive = false }
    compileOnly("me.lucko:fabric-permissions-api:0.7.0") { isTransitive = false }
    compileOnly("maven.modrinth:open-parties-and-claims:fabric-26.2-0.27.8") { isTransitive = false }
}
