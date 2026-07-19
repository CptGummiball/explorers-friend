// Minecraft 26.1 line (unobfuscated era, Java 25) - shares the 26.2 port source.
plugins {
    id("explorersfriend.platform-noremap")
}

sourceSets["main"].java.exclude("**/claims/provider/FtbChunksClaimProvider.java")
sourceSets["main"].java.exclude("**/claims/provider/OpacClaimProvider.java")
