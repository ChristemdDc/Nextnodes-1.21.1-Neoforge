package com.nextnodes.permissions;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Manages per-player head glyphs for the tab list.
 *
 * <p>Builds a Minecraft resource pack zip that defines a custom font
 * {@code nextnodes:heads}. Each player is assigned a private-use-area
 * Unicode character (U+E000 …) whose glyph is an 8×8 crop of their skin face.
 * The pack is rebuilt whenever a new player face is added.
 */
public final class PlayerHeadFont {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerHeadFont.class);
    /** First Unicode Private Use Area character; U+E000 */
    private static final int BASE_CHAR = 0xE000;
    private static final String RP_NAMESPACE = "nextnodes";
    private static final String RP_FONT = "heads";

    /** Ordered map UUID → assigned character so the pack is built deterministically. */
    private final Map<UUID, Character> charMap = new LinkedHashMap<>();
    /** Face PNG bytes, keyed by UUID. */
    private final Map<UUID, byte[]> faceBytes = new ConcurrentHashMap<>();

    private volatile byte[] packBytes;
    private volatile String packSha1 = "";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // ── public API ──────────────────────────────────────────────────────────

    /**
     * Returns (or assigns) the private-use-area character for this player's
     * head glyph. Always returns the same character for the same UUID.
     */
    public synchronized char getOrAssignChar(UUID uuid) {
        return charMap.computeIfAbsent(uuid, u -> (char) (BASE_CHAR + charMap.size()));
    }

    /** Returns true if a face texture has been fetched and stored for this player. */
    public boolean hasFace(UUID uuid) {
        return faceBytes.containsKey(uuid);
    }

    /** Current packed ZIP bytes, or {@code null} if the pack has not been built yet. */
    public byte[] packBytes() {
        return packBytes;
    }

    /** SHA-1 hex digest of the current pack, or {@code ""} if not yet built. */
    public String packSha1() {
        return packSha1;
    }

    /**
     * Attempts to fetch and extract the player's face from their skin.
     * If successful, the face is stored and the resource pack is rebuilt.
     *
     * @param uuid    player UUID
     * @param profile game profile (should contain the {@code textures} property)
     * @return {@code true} if a new face was added and the pack was rebuilt
     */
    public boolean updateFaceFromProfile(UUID uuid, GameProfile profile) {
        try {
            String skinUrl = getSkinUrl(profile);
            if (skinUrl == null) {
                LOGGER.debug("No skin URL for player {}", uuid);
                return false;
            }
            byte[] skinPng = downloadBytes(skinUrl);
            byte[] face = extractFace(skinPng);
            if (face == null) return false;

            faceBytes.put(uuid, face);
            getOrAssignChar(uuid); // ensure char is assigned before rebuild
            rebuildPack();
            return true;
        } catch (Exception e) {
            LOGGER.warn("Failed to build head glyph for {}: {}", uuid, e.getMessage());
            return false;
        }
    }

    // ── private helpers ─────────────────────────────────────────────────────

    /** Reads the SKIN url from the Base64-encoded {@code textures} GameProfile property. */
    private static String getSkinUrl(GameProfile profile) {
        Collection<Property> props = profile.getProperties().get("textures");
        if (props == null || props.isEmpty()) return null;
        String encoded = props.iterator().next().value();
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(decoded).getAsJsonObject();
            JsonObject textures = root.getAsJsonObject("textures");
            if (textures == null || !textures.has("SKIN")) return null;
            return textures.getAsJsonObject("SKIN").get("url").getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] downloadBytes(String url) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " for " + url);
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading skin", e);
        }
    }

    /**
     * Crops the 8×8 face region (inner head + hat overlay) from a Minecraft skin PNG.
     * Avoids {@code java.awt.Graphics2D} so it runs safely in headless server environments.
     */
    private static byte[] extractFace(byte[] skinPng) throws IOException {
        BufferedImage skin;
        try (ByteArrayInputStream bin = new ByteArrayInputStream(skinPng)) {
            skin = ImageIO.read(bin);
        }
        if (skin == null) return null;

        int size = 8;
        BufferedImage face = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);

        // Inner head layer: pixels (8,8) → (16,16) in the skin texture
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                face.setRGB(x, y, skin.getRGB(x + 8, y + 8));
            }
        }

        // Hat / overlay layer: pixels (40,8) → (48,16), only in 64×64 skins
        if (skin.getWidth() >= 56 && skin.getHeight() >= 32) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int hatPixel = skin.getRGB(x + 40, y + 8);
                    int alpha = (hatPixel >>> 24) & 0xFF;
                    if (alpha > 0) {
                        face.setRGB(x, y, blendArgb(face.getRGB(x, y), hatPixel));
                    }
                }
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        ImageIO.write(face, "PNG", out);
        return out.toByteArray();
    }

    /** Alpha-composites {@code src} over {@code dst} (both ARGB). */
    private static int blendArgb(int dst, int src) {
        int sa = (src >>> 24) & 0xFF;
        if (sa == 0) return dst;
        if (sa == 255) return src;
        int da = (dst >>> 24) & 0xFF;
        float sf = sa / 255f;
        float df = (da / 255f) * (1 - sf);
        float af = sf + df;
        if (af == 0) return 0;
        int r = (int) (((src >> 16 & 0xFF) * sf + (dst >> 16 & 0xFF) * df) / af);
        int g = (int) (((src >>  8 & 0xFF) * sf + (dst >>  8 & 0xFF) * df) / af);
        int b = (int) (((src       & 0xFF) * sf + (dst       & 0xFF) * df) / af);
        return ((int) (af * 255) << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Builds the resource pack ZIP in memory.
     * Called every time a new face is added; synchronized to prevent concurrent rebuilds.
     */
    private synchronized void rebuildPack() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(16384);
            try (ZipOutputStream zip = new ZipOutputStream(baos)) {

                // pack.mcmeta
                putEntry(zip, "pack.mcmeta",
                        "{\"pack\":{\"pack_format\":34,\"description\":\"NextNodes Player Heads\"}}"
                                .getBytes(StandardCharsets.UTF_8));

                // Build the list of bitmap font providers + texture files
                StringBuilder providers = new StringBuilder("[");
                boolean first = true;
                for (Map.Entry<UUID, Character> entry : charMap.entrySet()) {
                    UUID uuid = entry.getKey();
                    byte[] png = faceBytes.get(uuid);
                    if (png == null) continue;

                    String texName = uuid.toString().replace("-", "") + ".png";
                    // In the font JSON, characters must be included literally (UTF-8)
                    // so that Minecraft can map the glyph to the correct codepoint.
                    String charLiteral = String.valueOf(entry.getValue());

                    if (!first) providers.append(',');
                    first = false;
                    providers.append(String.format(
                            "{\"type\":\"bitmap\",\"file\":\"%s:font/%s\",\"ascent\":7,\"height\":8,\"chars\":[\"%s\"]}",
                            RP_NAMESPACE, texName, charLiteral));

                    putEntry(zip, "assets/" + RP_NAMESPACE + "/textures/font/" + texName, png);
                }
                providers.append(']');

                String fontJson = "{\"providers\":" + providers + "}";
                putEntry(zip, "assets/" + RP_NAMESPACE + "/font/" + RP_FONT + ".json",
                        fontJson.getBytes(StandardCharsets.UTF_8));
            }

            byte[] bytes = baos.toByteArray();
            this.packBytes = bytes;
            this.packSha1 = sha1Hex(bytes);
            LOGGER.debug("Player-head pack rebuilt: {} face(s), {} bytes, sha1={}",
                    charMap.size(), bytes.length, packSha1);

        } catch (IOException e) {
            LOGGER.error("Failed to rebuild player-head resource pack", e);
        }
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static String sha1Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
