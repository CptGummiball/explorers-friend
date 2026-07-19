// Minecraft 1.21.11: new PermissionPredicate command permissions (Perms shim).
// Integrations: FTB Chunks 2111.x, OPAC, JSON import.
plugins {
    id("explorersfriend.platform-yarn")
}

dependencies {
    modCompileOnly("eu.pb4:common-protection-api:1.0.0") { isTransitive = false }
    modCompileOnly("maven.modrinth:waystones:21.11.9+fabric-1.21.11") { isTransitive = false }
    modCompileOnly("me.lucko:fabric-permissions-api:0.6.1") { isTransitive = false }
    modCompileOnly("dev.ftb.mods:ftb-chunks-fabric:2111.1.1") { isTransitive = false }
    modCompileOnly("dev.ftb.mods:ftb-teams-fabric:2111.1.1") { isTransitive = false }
    modCompileOnly("dev.ftb.mods:ftb-library-fabric:2111.1.1") { isTransitive = false }
    modCompileOnly("dev.architectury:architectury-fabric:19.0.1") { isTransitive = false }
    modCompileOnly("maven.modrinth:open-parties-and-claims:fabric-1.21.11-0.27.8") { isTransitive = false }
}
