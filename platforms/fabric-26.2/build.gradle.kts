// Unobfuscated era (Minecraft 26.x): no yarn property -> loom runs without a mapping
// dependency; sources use official Mojang names. Java 25 toolchain.
plugins {
    id("explorersfriend.platform-noremap")
}

// Claim integrations are wired per era; availability for 26.x is checked in Phase E
// (providers excluded from compilation until their 26.x artifacts are confirmed).
sourceSets["main"].java.exclude("**/claims/provider/FtbChunksClaimProvider.java")
sourceSets["main"].java.exclude("**/claims/provider/OpacClaimProvider.java")
