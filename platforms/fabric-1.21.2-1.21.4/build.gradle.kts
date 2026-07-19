// Family module for Minecraft 1.21.2-1.21.4 (compile target: 1.21.2; the yarn-era
// APIs this adapter touches are binary-compatible across the family - verified by
// compiling the identical source against every member and smoke-testing each).
plugins {
    id("explorersfriend.platform-yarn")
}

dependencies {
    // OPAC has no 1.21.2/1.21.7 builds; adapter compiled against the oldest family
    // artifact, runtime-gated by isModLoaded as everywhere.
    modCompileOnly("maven.modrinth:open-parties-and-claims:fabric-1.21.3-0.25.10") { isTransitive = false }
}
