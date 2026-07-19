// Minecraft 26.1 line (unobfuscated era, Java 25) - shares the 26.2 port source.
plugins {
    id("explorersfriend.platform-noremap")
}

sourceSets["main"].java.exclude("**/claims/provider/FtbChunksClaimProvider.java")

dependencies {
    compileOnly("eu.pb4:common-protection-api:2.0.0") { isTransitive = false }
    compileOnly("maven.modrinth:waystones:26.1.2.10+fabric-26.1.2") { isTransitive = false }
    compileOnly("me.lucko:fabric-permissions-api:0.7.0") { isTransitive = false }
    compileOnly("maven.modrinth:open-parties-and-claims:fabric-26.1.2-0.27.8") { isTransitive = false }
}
