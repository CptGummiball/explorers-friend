// Family module for Minecraft 1.21.9-1.21.10 (authlib record accessors,
// Entity.getEntityWorld, spawn point moved into WorldProperties).
plugins {
    id("explorersfriend.platform-yarn")
}

dependencies {
    modCompileOnly("eu.pb4:common-protection-api:1.0.0") { isTransitive = false }
    modCompileOnly("maven.modrinth:waystones:21.10.5+fabric-1.21.10") { isTransitive = false }
    modCompileOnly("me.lucko:fabric-permissions-api:0.3.3") { isTransitive = false }
    modCompileOnly("maven.modrinth:open-parties-and-claims:fabric-1.21.9-0.25.6") { isTransitive = false }
}
