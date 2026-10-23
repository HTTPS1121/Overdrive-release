package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Authenticated media must never be cacheable by a shared cache. A
 * Cloudflare-proxied tunnel hostname caches .jpg/.mp4 by extension and, with
 * Origin Cache Control on, honours {@code public} even when the request
 * carried credentials — so a {@code public, max-age=86400} thumbnail could be
 * served to a visitor without a session. {@code private} keeps the browser
 * cache the 24 h was meant for.
 */
public class HttpResponseImageCacheControlTest {

    @Test
    public void sendImageDefaultsToPrivateDayLongCache() throws Exception {
        File image = File.createTempFile("thumb", ".jpg");
        try {
            Files.write(image.toPath(), new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            HttpResponse.sendImage(out, image, "image/jpeg");
            String response = out.toString("ISO-8859-1");
            assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"));
            assertTrue(response, response.contains("\r\nCache-Control: private, max-age=86400\r\n"));
            assertFalse(response, response.contains("public"));
        } finally {
            image.delete();
        }
    }

    @Test
    public void sendImageHonoursCallerCacheControl() throws Exception {
        File image = File.createTempFile("thumb", ".jpg");
        try {
            Files.write(image.toPath(), new byte[]{1, 2, 3});
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            HttpResponse.sendImage(out, image, "image/jpeg", HttpResponse.NO_STORE);
            assertTrue(out.toString("ISO-8859-1").contains("\r\nCache-Control: no-store\r\n"));
        } finally {
            image.delete();
        }
    }

    @Test
    public void sendImageBytesIsPrivateToo() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpResponse.sendImageBytes(out, new byte[]{9, 9, 9}, "image/webp");
        String response = out.toString("ISO-8859-1");
        assertTrue(response, response.contains("\r\nCache-Control: private, max-age=86400\r\n"));
        assertFalse(response, response.contains("public"));
        assertEquals("private, max-age=86400", HttpResponse.IMAGE_CACHE_CONTROL);
    }

    @Test
    public void thumbnailGeneratingPlaceholderIsNoStore() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RecordingsApiHandler.sendThumbnailGenerating(out);
        String response = out.toString("ISO-8859-1");
        assertTrue(response.startsWith("HTTP/1.1 202 Accepted\r\n"));
        assertTrue(response, response.contains("\r\nCache-Control: no-store\r\n"));
        assertTrue(response.contains("\r\nRetry-After: 1\r\n"));
        assertTrue(response.endsWith("{\"status\":\"generating\"}"));
    }

    @Test
    public void inFlightRecordingSourceIsDetectedByTmpSuffix() {
        assertTrue(RecordingsApiHandler.isInFlightSource(new File("/x/event_1.mp4.tmp")));
        assertFalse(RecordingsApiHandler.isInFlightSource(new File("/x/event_1.mp4")));
        assertFalse(RecordingsApiHandler.isInFlightSource(null));
    }

    /**
     * Source pin: thumbnails rendered from an in-flight .mp4.tmp go out
     * no-store, and nothing authenticated still says {@code public}.
     */
    @Test
    public void thumbnailServingUsesNoStoreForInFlightSources() throws Exception {
        String handler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/RecordingsApiHandler.java");
        int serve = handler.indexOf("private static void serveThumbnail(");
        int byId = handler.indexOf("private static void serveThumbnailById(");
        assertTrue(serve >= 0 && byId > serve);
        String region = handler.substring(serve, byId);
        assertTrue(region.contains("isInFlightSource(videoFile)"));
        assertTrue(region.contains("? HttpResponse.NO_STORE"));
        assertTrue(region.contains("HttpResponse.sendImage(out, heroSibling, \"image/jpeg\", thumbCacheControl);"));
        assertTrue(region.contains("HttpResponse.sendImage(out, thumbFile, \"image/jpeg\", thumbCacheControl);"));
        assertFalse(handler.contains("public, max-age"));

        String response = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/HttpResponse.java");
        assertFalse("no image helper may default to a shared-cacheable policy",
                response.contains("\"public, max-age"));
        assertFalse(response.contains("Cache-Control: public, max-age"));
    }

    private static String readRepositoryFile(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
