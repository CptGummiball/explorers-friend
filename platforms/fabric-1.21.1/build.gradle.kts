plugins {
    id("explorersfriend.platform-conventions")
}

dependencies {
    // Optional claim-system integrations for this Minecraft version (compile-only).
    modCompileOnly("dev.ftb.mods:ftb-chunks-fabric:2101.1.20") { isTransitive = false }
    modCompileOnly("dev.ftb.mods:ftb-teams-fabric:2101.1.10") { isTransitive = false }
    modCompileOnly("dev.ftb.mods:ftb-library-fabric:2101.1.33") { isTransitive = false }
    modCompileOnly("dev.architectury:architectury-fabric:13.0.6") { isTransitive = false }
    modCompileOnly("maven.modrinth:open-parties-and-claims:cNfQARzn") { isTransitive = false }
}
