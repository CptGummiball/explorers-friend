package net.explorersfriend.color;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.explorersfriend.resource.ResourcePaths;
import net.explorersfriend.resource.ResourcePool;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a block to the texture that best represents it from above:
 * blockstate JSON → model chain (parents, cycle-guarded) → merged texture variables →
 * preferred top-facing texture.
 *
 * <p>Pure logic over a {@link ResourcePool}; no Minecraft classes, fully unit-testable.
 * Parsed models are memoized per resolver instance (thread-safe map), so shared parents
 * like {@code block/cube_all} are parsed exactly once per scan.</p>
 */
public final class ModelResolver {

    private static final int MAX_PARENT_DEPTH = 12;
    private static final int MAX_VARIABLE_HOPS = 12;
    /** Merged-texture keys tried when no element data decides, in order. */
    private static final String[] PREFERRED_KEYS = {
            "top", "end", "up", "all", "texture", "layer0", "cross", "plant", "pattern", "particle"
    };

    /** Outcome of a block resolution. {@code via} documents the path taken (for metadata). */
    public record Resolution(String modelId, String textureId, boolean tinted, String via) {
    }

    private record ParsedModel(String parent, Map<String, String> textures, JsonArray elements) {
    }

    /** Marker for "we tried and failed", so failures are memoized too. */
    private static final ParsedModel INVALID = new ParsedModel(null, Map.of(), null);

    private final ResourcePool pool;
    private final ConcurrentHashMap<String, ParsedModel> modelCache = new ConcurrentHashMap<>();

    public ModelResolver(ResourcePool pool) {
        this.pool = pool;
    }

    /**
     * @return the resolution, or {@code null} when neither blockstate nor any fallback
     *         produced a usable texture.
     */
    public Resolution resolveBlock(String blockId) {
        String normalized = ResourcePaths.normalizeId(blockId);
        List<String> modelIds = modelsFromBlockstate(normalized);
        String via = "blockstate";
        if (modelIds.isEmpty()) {
            // No/broken blockstate: try the conventional direct model location.
            modelIds = List.of(ResourcePaths.namespaceOf(normalized) + ":block/" + ResourcePaths.pathOf(normalized));
            via = "direct-model";
        }
        for (String modelId : modelIds) {
            Resolution resolution = resolveModel(modelId, via);
            if (resolution != null) {
                return resolution;
            }
        }
        return null;
    }

    /** Parses the blockstate file and returns candidate model ids (deterministic order). */
    List<String> modelsFromBlockstate(String blockId) {
        byte[] data = pool.read(ResourcePaths.blockstatePath(blockId));
        if (data == null) {
            return List.of();
        }
        try {
            JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> models = new ArrayList<>();
            if (root.has("variants") && root.get("variants").isJsonObject()) {
                // Deterministic pick: the unconditional variant "" first, then sorted keys.
                TreeMap<String, JsonElement> variants = new TreeMap<>();
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("variants").entrySet()) {
                    variants.put(entry.getKey(), entry.getValue());
                }
                JsonElement preferred = variants.containsKey("") ? variants.get("") : null;
                if (preferred != null) {
                    addModelRefs(preferred, models);
                }
                for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
                    if (!entry.getKey().isEmpty()) {
                        addModelRefs(entry.getValue(), models);
                    }
                }
            } else if (root.has("multipart") && root.get("multipart").isJsonArray()) {
                // Prefer unconditional parts (always rendered), then conditional ones.
                List<JsonElement> unconditional = new ArrayList<>();
                List<JsonElement> conditional = new ArrayList<>();
                for (JsonElement part : root.getAsJsonArray("multipart")) {
                    if (!part.isJsonObject()) {
                        continue;
                    }
                    JsonObject partObj = part.getAsJsonObject();
                    (partObj.has("when") ? conditional : unconditional).add(partObj.get("apply"));
                }
                for (JsonElement apply : unconditional) {
                    addModelRefs(apply, models);
                }
                for (JsonElement apply : conditional) {
                    addModelRefs(apply, models);
                }
            }
            return models;
        } catch (Exception e) {
            return List.of(); // broken JSON -> caller falls back
        }
    }

    private static void addModelRefs(JsonElement variantValue, List<String> out) {
        if (variantValue == null) {
            return;
        }
        if (variantValue.isJsonArray()) {
            for (JsonElement el : variantValue.getAsJsonArray()) {
                addModelRefs(el, out);
            }
            return;
        }
        if (variantValue.isJsonObject()) {
            JsonElement model = variantValue.getAsJsonObject().get("model");
            if (model != null && model.isJsonPrimitive()) {
                String id = ResourcePaths.normalizeId(model.getAsString());
                if (!out.contains(id)) {
                    out.add(id);
                }
            }
        }
    }

    /** Walks the parent chain of one model and picks the representative texture. */
    Resolution resolveModel(String modelId, String via) {
        Map<String, String> mergedTextures = new HashMap<>();
        JsonArray elements = null;
        Set<String> visited = new HashSet<>();
        String current = ResourcePaths.normalizeId(modelId);
        int depth = 0;
        while (current != null && depth++ < MAX_PARENT_DEPTH) {
            if (!visited.add(current)) {
                return null; // circular parent chain
            }
            ParsedModel model = loadModel(current);
            if (model == INVALID) {
                break;
            }
            // Child values win; only add keys we have not seen yet.
            for (Map.Entry<String, String> entry : model.textures().entrySet()) {
                mergedTextures.putIfAbsent(entry.getKey(), entry.getValue());
            }
            if (elements == null && model.elements() != null) {
                elements = model.elements();
            }
            current = model.parent();
        }
        if (mergedTextures.isEmpty()) {
            return null;
        }

        boolean tinted = false;
        String textureRef = null;
        if (elements != null) {
            // Preferred signal: what does the "up" face actually show?
            for (JsonElement el : elements) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject faces = el.getAsJsonObject().has("faces")
                        ? el.getAsJsonObject().getAsJsonObject("faces") : null;
                if (faces == null) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> face : faces.entrySet()) {
                    if (!face.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject faceObj = face.getValue().getAsJsonObject();
                    if (faceObj.has("tintindex")) {
                        tinted = true;
                    }
                    if (textureRef == null && "up".equals(face.getKey()) && faceObj.has("texture")) {
                        textureRef = faceObj.get("texture").getAsString();
                    }
                }
            }
        }
        if (textureRef == null) {
            for (String key : PREFERRED_KEYS) {
                if (mergedTextures.containsKey(key)) {
                    textureRef = "#" + key;
                    break;
                }
            }
        }
        if (textureRef == null) {
            // Last resort: any texture variable, deterministic by key order.
            textureRef = "#" + new TreeMap<>(mergedTextures).firstKey();
        }
        String textureId = resolveTextureVariable(textureRef, mergedTextures);
        if (textureId == null) {
            return null;
        }
        return new Resolution(ResourcePaths.normalizeId(modelId), ResourcePaths.normalizeId(textureId), tinted, via);
    }

    /** Follows "#var" chains through the merged texture map. */
    static String resolveTextureVariable(String ref, Map<String, String> textures) {
        String current = ref;
        for (int hop = 0; hop < MAX_VARIABLE_HOPS; hop++) {
            if (current == null) {
                return null;
            }
            if (!current.startsWith("#")) {
                return current;
            }
            current = textures.get(current.substring(1));
        }
        return null; // variable cycle
    }

    private ParsedModel loadModel(String modelId) {
        return modelCache.computeIfAbsent(modelId, id -> {
            byte[] data = pool.read(ResourcePaths.modelPath(id));
            if (data == null) {
                return INVALID;
            }
            try {
                JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
                String parent = null;
                if (root.has("parent") && root.get("parent").isJsonPrimitive()) {
                    parent = ResourcePaths.normalizeId(root.get("parent").getAsString());
                    if ("minecraft:builtin/generated".equals(parent) || "minecraft:builtin/entity".equals(parent)) {
                        parent = null; // built-ins have no file; treat as chain end
                    }
                }
                Map<String, String> textures = new HashMap<>();
                if (root.has("textures") && root.get("textures").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("textures").entrySet()) {
                        if (entry.getValue().isJsonPrimitive()) {
                            textures.put(entry.getKey(), entry.getValue().getAsString());
                        }
                    }
                }
                JsonArray elements = root.has("elements") && root.get("elements").isJsonArray()
                        ? root.getAsJsonArray("elements") : null;
                return new ParsedModel(parent, Map.copyOf(textures), elements);
            } catch (Exception e) {
                return INVALID;
            }
        });
    }

    /** Number of distinct model files parsed (for the scan summary). */
    public int parsedModelCount() {
        return modelCache.size();
    }
}
