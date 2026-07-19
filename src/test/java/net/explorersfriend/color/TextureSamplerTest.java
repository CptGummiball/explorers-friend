package net.explorersfriend.color;

import net.explorersfriend.testutil.TestImages;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextureSamplerTest {

    private static final byte[] ANIMATION_MCMETA =
            "{\"animation\":{\"frametime\":2}}".getBytes(StandardCharsets.UTF_8);

    @Test
    void solidTextureYieldsItsColor() throws IOException {
        TextureSampler sampler = new TextureSampler(4096, TextureSampler.MODE_FIRST_FRAME);
        assertEquals(0xFF112233, sampler.sample(TestImages.solidPng(16, 16, 0xFF112233), null));
    }

    @Test
    void firstFrameModeUsesOnlyTheFirstFrame() throws IOException {
        byte[] png = TestImages.framesPng(16, 0xFFFF0000, 0xFF0000FF);
        TextureSampler firstFrame = new TextureSampler(4096, TextureSampler.MODE_FIRST_FRAME);
        assertEquals(0xFFFF0000, firstFrame.sample(png, ANIMATION_MCMETA));
    }

    @Test
    void averageModeMixesAllFrames() throws IOException {
        byte[] png = TestImages.framesPng(16, 0xFFFF0000, 0xFF0000FF);
        TextureSampler average = new TextureSampler(4096, TextureSampler.MODE_AVERAGE);
        int color = average.sample(png, ANIMATION_MCMETA);
        assertNotEquals(0xFFFF0000, color);
        assertNotEquals(0xFF0000FF, color);
    }

    @Test
    void tallTextureWithoutMcmetaIsNotTreatedAsAnimation() throws IOException {
        byte[] png = TestImages.framesPng(16, 0xFFFF0000, 0xFF0000FF);
        TextureSampler sampler = new TextureSampler(4096, TextureSampler.MODE_FIRST_FRAME);
        int color = sampler.sample(png, null);
        assertNotEquals(0xFFFF0000, color, "without mcmeta all pixels count");
    }

    @Test
    void oversizedTextureIsRejected() {
        TextureSampler sampler = new TextureSampler(64, TextureSampler.MODE_FIRST_FRAME);
        assertThrows(IOException.class, () -> sampler.sample(TestImages.solidPng(128, 16, 0xFF000000), null));
    }

    @Test
    void garbageBytesAreRejected() {
        TextureSampler sampler = new TextureSampler(4096, TextureSampler.MODE_FIRST_FRAME);
        assertThrows(IOException.class, () -> sampler.sample("not a png".getBytes(StandardCharsets.UTF_8), null));
    }

    @Test
    void brokenMcmetaIsTolerated() throws IOException {
        byte[] png = TestImages.solidPng(16, 16, 0xFF445566);
        TextureSampler sampler = new TextureSampler(4096, TextureSampler.MODE_FIRST_FRAME);
        assertEquals(0xFF445566, sampler.sample(png, "{broken".getBytes(StandardCharsets.UTF_8)));
    }
}
