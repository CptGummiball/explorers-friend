package net.explorersfriend.world;

import net.explorersfriend.ExplorersFriend;
import net.explorersfriend.color.ColormapSampler;
import net.explorersfriend.render.RuntimePalette;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeEffects;

import java.util.HashMap;
import java.util.Map;

/**
 * Precomputed grass/foliage/water tints for every registered biome, indexed by biome
 * raw id (live path) and by biome id string (region-file path). Climate values come
 * from the biome registry ({@code Biome.weather} via access widener); colors come from
 * the vanilla colormaps sampled at runtime, with {@link BiomeEffects} overrides and
 * grass color modifiers (swamp/dark forest) applied like the game does.
 *
 * <p>Built once on the scan pool after registries are frozen; immutable afterwards.</p>
 */
public final class BiomeTintTable {

    private final int[] grassByRawId;
    private final int[] foliageByRawId;
    private final int[] waterByRawId;
    private final Map<String, int[]> byName;

    private BiomeTintTable(int[] grass, int[] foliage, int[] water, Map<String, int[]> byName) {
        this.grassByRawId = grass;
        this.foliageByRawId = foliage;
        this.waterByRawId = water;
        this.byName = byName;
    }

    public static BiomeTintTable build(Registry<Biome> registry,
                                       ColormapSampler grassMap,
                                       ColormapSampler foliageMap) {
        int size = registry.size();
        int[] grass = new int[size];
        int[] foliage = new int[size];
        int[] water = new int[size];
        Map<String, int[]> byName = new HashMap<>();
        for (Biome biome : registry) {
            int rawId = registry.getRawId(biome);
            Identifier id = registry.getId(biome);
            float temperature = biome.getTemperature();
            float downfall = biome.weather.downfall();
            BiomeEffects effects = biome.getEffects();

            int grassColor = effects.grassColor()
                    .orElseGet(() -> grassMap.sample(temperature, downfall));
            grassColor = applyGrassModifier(grassColor, effects.grassColorModifier());
            int foliageColor = effects.foliageColor()
                    .orElseGet(() -> foliageMap.sample(temperature, downfall));
            int waterColor = effects.waterColor();

            if (rawId >= 0 && rawId < size) {
                grass[rawId] = grassColor;
                foliage[rawId] = foliageColor;
                water[rawId] = waterColor;
            }
            if (id != null) {
                byName.put(id.toString(), new int[]{grassColor, foliageColor, waterColor});
            }
        }
        ExplorersFriend.LOGGER.info("[ExplorersFriend/Colors] Computed biome tints for {} biome(s)", byName.size());
        return new BiomeTintTable(grass, foliage, water, byName);
    }

    /** Behaviour equivalent of the vanilla grass color modifiers. */
    static int applyGrassModifier(int grassColor, BiomeEffects.GrassColorModifier modifier) {
        return switch (modifier) {
            case DARK_FOREST -> ((grassColor & 0xFEFEFE) + 0x28340A) >> 1;
            case SWAMP -> 0x6A7039;
            case NONE -> grassColor;
        };
    }

    public int grass(int biomeRawId) {
        return biomeRawId >= 0 && biomeRawId < grassByRawId.length
                ? grassByRawId[biomeRawId] : RuntimePalette.DEFAULT_GRASS_RGB;
    }

    public int foliage(int biomeRawId) {
        return biomeRawId >= 0 && biomeRawId < foliageByRawId.length
                ? foliageByRawId[biomeRawId] : RuntimePalette.DEFAULT_FOLIAGE_RGB;
    }

    public int water(int biomeRawId) {
        return biomeRawId >= 0 && biomeRawId < waterByRawId.length
                ? waterByRawId[biomeRawId] : RuntimePalette.DEFAULT_WATER_RGB;
    }

    public int rawIdOf(Registry<Biome> registry, RegistryEntry<Biome> entry) {
        return registry.getRawId(entry.value());
    }

    /** Name-keyed view for the region-file render path. */
    public Map<String, int[]> nameView() {
        return byName;
    }
}
