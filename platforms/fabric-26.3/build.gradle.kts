// Minecraft 26.3 (official, unobfuscated). JSON import and Common Protection API.
plugins {
    id("explorersfriend.platform-noremap")
}

sourceSets["main"].java.exclude("**/claims/provider/FtbChunksClaimProvider.java")
sourceSets["main"].java.exclude("**/claims/provider/OpacClaimProvider.java")

dependencies {
    compileOnly("eu.pb4:common-protection-api:2.0.0") { isTransitive = false }
    compileOnly("me.lucko:fabric-permissions-api:0.7.0") { isTransitive = false }
}
