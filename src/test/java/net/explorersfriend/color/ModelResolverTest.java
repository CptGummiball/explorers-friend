package net.explorersfriend.color;

import net.explorersfriend.testutil.TestResources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelResolverTest {

    private static TestResources vanillaBase() {
        return new TestResources("test")
                .model("minecraft:block/cube", """
                        {"elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{
                          "down":{"texture":"#down"},"up":{"texture":"#up"},
                          "north":{"texture":"#north"},"south":{"texture":"#south"},
                          "west":{"texture":"#west"},"east":{"texture":"#east"}}}]}
                        """)
                .model("minecraft:block/cube_all", """
                        {"parent":"block/cube","textures":{"particle":"#all","down":"#all","up":"#all",
                         "north":"#all","east":"#all","south":"#all","west":"#all"}}
                        """)
                .model("minecraft:block/cube_bottom_top", """
                        {"parent":"block/cube","textures":{"particle":"#side","down":"#bottom","up":"#top",
                         "north":"#side","east":"#side","south":"#side","west":"#side"}}
                        """);
    }

    @Test
    void resolvesSimpleCubeAllChain() {
        TestResources resources = vanillaBase()
                .blockstate("minecraft:stone", "{\"variants\":{\"\":{\"model\":\"minecraft:block/stone\"}}}")
                .model("minecraft:block/stone", "{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"minecraft:block/stone\"}}");
        ModelResolver resolver = new ModelResolver(resources.pool());
        ModelResolver.Resolution resolution = resolver.resolveBlock("minecraft:stone");
        assertNotNull(resolution);
        assertEquals("minecraft:block/stone", resolution.textureId());
        assertEquals("blockstate", resolution.via());
        assertFalse(resolution.tinted());
    }

    @Test
    void prefersTopTextureFromUpFace() {
        TestResources resources = vanillaBase()
                .blockstate("minecraft:grass_block", """
                        {"variants":{"snowy=false":{"model":"minecraft:block/grass_block"},
                                     "snowy=true":{"model":"minecraft:block/grass_block_snow"}}}
                        """)
                .model("minecraft:block/grass_block", """
                        {"parent":"block/cube_bottom_top","textures":{
                          "bottom":"minecraft:block/dirt","top":"minecraft:block/grass_block_top",
                          "side":"minecraft:block/grass_block_side"}}
                        """);
        ModelResolver resolver = new ModelResolver(resources.pool());
        ModelResolver.Resolution resolution = resolver.resolveBlock("minecraft:grass_block");
        assertNotNull(resolution);
        assertEquals("minecraft:block/grass_block_top", resolution.textureId(),
                "the up face must win over side/bottom");
    }

    @Test
    void detectsTintIndex() {
        TestResources resources = new TestResources("test")
                .blockstate("minecraft:tinted", "{\"variants\":{\"\":{\"model\":\"minecraft:block/tinted\"}}}")
                .model("minecraft:block/tinted", """
                        {"textures":{"t":"minecraft:block/tinted_tex"},
                         "elements":[{"faces":{"up":{"texture":"#t","tintindex":0}}}]}
                        """);
        ModelResolver.Resolution resolution = new ModelResolver(resources.pool()).resolveBlock("minecraft:tinted");
        assertNotNull(resolution);
        assertTrue(resolution.tinted());
        assertEquals("minecraft:block/tinted_tex", resolution.textureId());
    }

    @Test
    void followsNestedTextureVariables() {
        TestResources resources = new TestResources("test")
                .blockstate("minecraft:chained", "{\"variants\":{\"\":{\"model\":\"minecraft:block/chained\"}}}")
                .model("minecraft:block/chained", """
                        {"parent":"minecraft:block/chain_parent","textures":{"base":"minecraft:block/real"}}
                        """)
                .model("minecraft:block/chain_parent", """
                        {"textures":{"middle":"#base","top":"#middle"},
                         "elements":[{"faces":{"up":{"texture":"#top"}}}]}
                        """);
        ModelResolver.Resolution resolution = new ModelResolver(resources.pool()).resolveBlock("minecraft:chained");
        assertNotNull(resolution);
        assertEquals("minecraft:block/real", resolution.textureId());
    }

    @Test
    void multipartPrefersUnconditionalParts() {
        TestResources resources = new TestResources("test")
                .blockstate("minecraft:wall", """
                        {"multipart":[
                          {"when":{"north":"true"},"apply":{"model":"minecraft:block/wall_side"}},
                          {"apply":{"model":"minecraft:block/wall_post"}}]}
                        """)
                .model("minecraft:block/wall_post", "{\"textures\":{\"texture\":\"minecraft:block/stone\"}}")
                .model("minecraft:block/wall_side", "{\"textures\":{\"texture\":\"minecraft:block/wrong\"}}");
        ModelResolver.Resolution resolution = new ModelResolver(resources.pool()).resolveBlock("minecraft:wall");
        assertNotNull(resolution);
        assertEquals("minecraft:block/stone", resolution.textureId());
    }

    @Test
    void circularParentChainFailsGracefully() {
        TestResources resources = new TestResources("test")
                .blockstate("minecraft:loop", "{\"variants\":{\"\":{\"model\":\"minecraft:block/loop_a\"}}}")
                .model("minecraft:block/loop_a", "{\"parent\":\"minecraft:block/loop_b\"}")
                .model("minecraft:block/loop_b", "{\"parent\":\"minecraft:block/loop_a\"}");
        assertNull(new ModelResolver(resources.pool()).resolveBlock("minecraft:loop"));
    }

    @Test
    void circularTextureVariablesFailGracefully() {
        TestResources resources = new TestResources("test")
                .blockstate("minecraft:varloop", "{\"variants\":{\"\":{\"model\":\"minecraft:block/varloop\"}}}")
                .model("minecraft:block/varloop",
                        "{\"textures\":{\"a\":\"#b\",\"b\":\"#a\"},\"elements\":[{\"faces\":{\"up\":{\"texture\":\"#a\"}}}]}");
        assertNull(new ModelResolver(resources.pool()).resolveBlock("minecraft:varloop"));
    }

    @Test
    void brokenBlockstateFallsBackToDirectModel() {
        TestResources resources = new TestResources("test")
                .blockstate("minecraft:broken", "{this is not json")
                .model("minecraft:block/broken", "{\"textures\":{\"all\":\"minecraft:block/broken_tex\"}}");
        ModelResolver.Resolution resolution = new ModelResolver(resources.pool()).resolveBlock("minecraft:broken");
        assertNotNull(resolution);
        assertEquals("direct-model", resolution.via());
        assertEquals("minecraft:block/broken_tex", resolution.textureId());
    }

    @Test
    void missingEverythingReturnsNull() {
        assertNull(new ModelResolver(new TestResources("test").pool()).resolveBlock("minecraft:nothing"));
    }

    @Test
    void particleIsLastResort() {
        TestResources resources = new TestResources("test")
                .blockstate("minecraft:water", "{\"variants\":{\"\":{\"model\":\"minecraft:block/water\"}}}")
                .model("minecraft:block/water", "{\"textures\":{\"particle\":\"minecraft:block/water_still\"}}");
        ModelResolver.Resolution resolution = new ModelResolver(resources.pool()).resolveBlock("minecraft:water");
        assertNotNull(resolution);
        assertEquals("minecraft:block/water_still", resolution.textureId());
    }
}
