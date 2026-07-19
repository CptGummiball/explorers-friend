// Minecraft 1.21.11: new PermissionPredicate command permissions (Perms shim).
// FTB Chunks 2111.x exists but its adapter is deferred (documented in the matrix);
// OPAC + JSON import are active.
plugins {
    id("explorersfriend.platform-yarn")
}

dependencies {
    modCompileOnly("maven.modrinth:open-parties-and-claims:fabric-1.21.11-0.27.8") { isTransitive = false }
}
