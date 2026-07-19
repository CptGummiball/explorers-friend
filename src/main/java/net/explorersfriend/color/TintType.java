package net.explorersfriend.color;

/** How a block's base color is tinted at render time based on the biome. */
public enum TintType {
    NONE,
    GRASS,
    FOLIAGE,
    WATER;

    public static TintType fromName(String name) {
        for (TintType value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return NONE;
    }
}
