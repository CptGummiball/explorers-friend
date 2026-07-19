package net.explorersfriend.color;

/**
 * Traceable result of the color resolution for one block: the color itself plus enough
 * metadata to explain <em>why</em> ("which model, which texture, from which JAR, with
 * which algorithm version"). {@code fallbackReason} is {@code null} for regular
 * resolutions and a short explanation otherwise.
 */
public record BlockColorResult(
        String blockId,
        int argb,
        TintType tint,
        String sourceId,
        String modelId,
        String textureId,
        String fallbackReason,
        int algorithmVersion,
        long resolvedAtEpochMs) {

    public boolean isFallback() {
        return fallbackReason != null;
    }
}
