// Unobfuscated era platforms (Minecraft >= 26.1): loom's no-remap pipeline.
plugins {
    id("net.fabricmc.fabric-loom")
    `java-library`
}

configureEfPlatform(yarnEra = false)
