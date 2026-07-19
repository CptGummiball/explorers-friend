// Family module for Minecraft 1.21.9-1.21.10 (authlib record accessors,
// Entity.getEntityWorld, spawn point moved into WorldProperties).
plugins {
    id("explorersfriend.platform-yarn")
}

dependencies {
    modCompileOnly("maven.modrinth:open-parties-and-claims:fabric-1.21.9-0.25.6") { isTransitive = false }
}
