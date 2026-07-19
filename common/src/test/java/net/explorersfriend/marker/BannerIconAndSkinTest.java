package net.explorersfriend.marker;

import net.explorersfriend.testutil.TestImages;
import net.explorersfriend.player.SkinImages;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BannerIconAndSkinTest {

    @Test
    void identicalBannerDesignsShareOneCachedIcon() {
        BannerIconRenderer renderer = new BannerIconRenderer(null); // fallback rendering
        String hashA = renderer.hashFor("base=red;stripe_top:white");
        String hashB = renderer.hashFor("base=red;stripe_top:white");
        String hashC = renderer.hashFor("base=blue;stripe_top:white");
        assertEquals(hashA, hashB, "identical designs deduplicate");
        assertNotEquals(hashA, hashC, "different designs differ");
        assertNotNull(renderer.pngForHash(hashA));
        assertNull(renderer.pngForHash("0123456789abcdef"), "unknown hashes yield nothing");
    }

    @Test
    void fallbackBannerIconIsAValidPng() throws Exception {
        BannerIconRenderer renderer = new BannerIconRenderer(null);
        byte[] png = renderer.pngForHash(renderer.hashFor("base=lime;cross:black;border:white"));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        assertEquals(20, image.getWidth());
        assertEquals(40, image.getHeight());
    }

    @Test
    void skinUrlValidationPinsMojangHost() {
        assertEquals("https://textures.minecraft.net/texture/abc123",
                SkinImages.skinUrlFromTexturesProperty(texturesJson("http://textures.minecraft.net/texture/abc123")));
        assertNull(SkinImages.skinUrlFromTexturesProperty(texturesJson("https://evil.example.com/skin.png")),
                "foreign hosts are rejected (SSRF guard)");
        assertNull(SkinImages.skinUrlFromTexturesProperty(texturesJson("file:///etc/passwd")));
        assertNull(SkinImages.skinUrlFromTexturesProperty("not-base64!!"));
    }

    private static String texturesJson(String url) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void headCompositionUsesHatLayerAndUpscales() throws Exception {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 8; y < 16; y++) {
            for (int x = 8; x < 16; x++) {
                skin.setRGB(x, y, 0xFF112233); // base head
            }
        }
        skin.setRGB(40, 8, 0xFFFF0000); // one hat pixel over the top-left head pixel
        byte[] head = SkinImages.composeHead(TestImages.encode(skin));
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(head));
        assertEquals(64, result.getWidth());
        assertEquals(0xFFFF0000, result.getRGB(0, 0), "hat layer wins where present");
        assertEquals(0xFF112233, result.getRGB(63, 63), "base skin elsewhere");
    }
}
