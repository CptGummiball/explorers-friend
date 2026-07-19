package net.explorersfriend.testutil;

import net.explorersfriend.resource.ResourcePool;
import net.explorersfriend.resource.ResourceSource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory resource fixtures for the resolver/extractor tests. */
public final class TestResources implements ResourceSource {

    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final String sourceId;

    public TestResources(String sourceId) {
        this.sourceId = sourceId;
    }

    public TestResources put(String path, String content) {
        files.put(path, content.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    public TestResources put(String path, byte[] content) {
        files.put(path, content);
        return this;
    }

    public TestResources blockstate(String blockId, String json) {
        return put("assets/" + namespace(blockId) + "/blockstates/" + path(blockId) + ".json", json);
    }

    public TestResources model(String modelId, String json) {
        return put("assets/" + namespace(modelId) + "/models/" + path(modelId) + ".json", json);
    }

    public TestResources texture(String textureId, byte[] png) {
        return put("assets/" + namespace(textureId) + "/textures/" + path(textureId) + ".png", png);
    }

    public ResourcePool pool() {
        return new ResourcePool(List.of(this));
    }

    private static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(0, colon) : "minecraft";
    }

    private static String path(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    @Override
    public byte[] read(String path) {
        return files.get(path);
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public String describe() {
        return "test fixture '" + sourceId + "'";
    }

    @Override
    public void close() {
        // nothing to release
    }
}
