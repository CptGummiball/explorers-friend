package net.explorersfriend.overlay;

import com.google.gson.JsonObject;

/**
 * One spatially indexable element of an overlay layer (a claim, a marker, …).
 * Implementations are immutable and already privacy-filtered — whatever
 * {@link #toJson()} returns goes to the browser verbatim.
 */
public interface OverlayItem {

    String id();

    String dimensionSlug();

    /** Bounding box in block coordinates (inclusive). */
    int minX();

    int minZ();

    int maxX();

    int maxZ();

    JsonObject toJson();
}
