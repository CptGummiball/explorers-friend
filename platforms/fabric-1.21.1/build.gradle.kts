plugins {
    id("explorersfriend.platform-yarn")
}

dependencies {
    modCompileOnly("eu.pb4:common-protection-api:1.0.0") { isTransitive = false }
    modCompileOnly("maven.modrinth:waystones:21.1.37+fabric-1.21.1") { isTransitive = false }
    modCompileOnly("me.lucko:fabric-permissions-api:0.3.3") { isTransitive = false }
    // Optional claim-system integrations for this Minecraft version (compile-only).
    modCompileOnly("dev.ftb.mods:ftb-chunks-fabric:2101.1.20") { isTransitive = false }
    modCompileOnly("dev.ftb.mods:ftb-teams-fabric:2101.1.10") { isTransitive = false }
    modCompileOnly("dev.ftb.mods:ftb-library-fabric:2101.1.33") { isTransitive = false }
    modCompileOnly("dev.architectury:architectury-fabric:13.0.6") { isTransitive = false }
    modCompileOnly("maven.modrinth:open-parties-and-claims:cNfQARzn") { isTransitive = false }
}
