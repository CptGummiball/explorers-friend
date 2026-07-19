package net.explorersfriend.player;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Pure skin/head image logic shared by every platform: profile-property parsing with
 * the SSRF host pin, and head composition (8×8 base + hat layer → 64×64 PNG).
 */
public final class SkinImages {

    public static final String ALLOWED_HOST = "textures.minecraft.net";

    private SkinImages() {
    }

    /** Parses the profile "textures" property; only Mojang's texture host is accepted. */
    public static String skinUrlFromTexturesProperty(String base64) {
        try {
            JsonObject root = JsonParser.parseString(
                    new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject skin = root.getAsJsonObject("textures").getAsJsonObject("SKIN");
            String url = skin.get("url").getAsString();
            URI uri = URI.create(url);
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                return null;
            }
            if (!ALLOWED_HOST.equalsIgnoreCase(uri.getHost())) {
                return null; // SSRF guard: never fetch from anywhere else
            }
            return "https://" + ALLOWED_HOST + uri.getRawPath();
        } catch (Exception e) {
            return null;
        }
    }

    /** 8×8 head + hat layer → 64×64 nearest-neighbour PNG. */
    public static byte[] composeHead(byte[] skinPng) throws IOException {
        BufferedImage skin = ImageIO.read(new ByteArrayInputStream(skinPng));
        if (skin == null || skin.getWidth() < 64 || skin.getHeight() < 32) {
            throw new IOException("not a skin texture");
        }
        BufferedImage head = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int base = skin.getRGB(8 + x, 8 + y);
                int hat = skin.getRGB(40 + x, 8 + y);
                int pixel = (hat >>> 24) > 8 ? hat : base;
                pixel |= 0xFF000000;
                for (int dy = 0; dy < 8; dy++) {
                    for (int dx = 0; dx < 8; dx++) {
                        head.setRGB(x * 8 + dx, y * 8 + dy, pixel);
                    }
                }
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(head, "png", out);
        return out.toByteArray();
    }
}
