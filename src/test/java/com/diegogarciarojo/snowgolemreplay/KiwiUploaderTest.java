package com.diegogarciarojo.snowgolemreplay;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KiwiUploaderTest {
    @TempDir Path dir;
    HttpServer server;
    Set<Integer> received;
    List<Integer> order;
    int posts;
    boolean rejectThird, neverComplete;
    @BeforeEach void setup() throws Exception {
        received = new HashSet<>(); order = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int code = 200; String response = "{}";
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/v2/folders")) {
                posts++;
                var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                assertTrue(body.has("encryption"));
                assertFalse(body.toString().contains("fixture.mcpr"));
                assertEquals(10, body.getAsJsonArray("files").get(0).getAsJsonObject().get("filesize").getAsInt());
                response = """
                    {"folderId":"folder","folderUrl":"https://file.kiwi/folder","uploadAuth":"auth",
                     "files":[{"fileId":"file","chunks":3,"chunkSize":4,"uploadUrls":{
                     "head":"http://127.0.0.1:%d/","path":"opaque/key","tail":"signed=yes",
                     "signatures":["s1","s2","s3"],"headers":{"x-amz-meta-folder_id":"folder"}}}]}
                    """.formatted(server.getAddress().getPort());
            } else if (path.endsWith("/upload-status")) {
                assertTrue(exchange.getRequestURI().getQuery().contains("uploadAuth=auth"));
                var missing = new ArrayList<Integer>();
                for (int i = 1; i <= 3; i++) if (!received.contains(i)) missing.add(i);
                response = "{\"complete\":" + (missing.isEmpty() && !neverComplete) + ",\"missing\":" + missing + "}";
            } else if (path.startsWith("/opaque/key/")) {
                int number = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
                assertEquals("folder", exchange.getRequestHeaders().getFirst("x-amz-meta-folder_id"));
                assertTrue(exchange.getRequestURI().getQuery().contains("X-Amz-Signature=s" + number));
                byte[] encrypted = exchange.getRequestBody().readAllBytes();
                assertTrue(encrypted.length > 21 + 16);
                order.add(number);
                if (rejectThird && number == 3) code = 400;
                else received.add(number);
            } else code = 404;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        Files.write(dir.resolve("fixture.mcpr"), new byte[10]);
    }
    @AfterEach void stop() { server.stop(0); }
    KiwiUploader uploader() { return new KiwiUploader(URI.create("http://127.0.0.1:" + server.getAddress().getPort())); }
    @Test void uploadsInRecommendedOrderWithHeadersAndVerifiedLink() throws Exception {
        String link = uploader().upload(dir.resolve("fixture.mcpr"), dir.resolve("resume.json"));
        assertEquals(List.of(1, 3, 2), order);
        assertTrue(link.matches("https://file.kiwi/folder#[A-Za-z0-9_-]{22}"));
        assertEquals(1, posts);
    }
    @Test void resumesSameFolderAfterFailureWithoutRepeatingSuccessfulChunks() throws Exception {
        rejectThird = true;
        assertThrows(Exception.class, () -> uploader().upload(dir.resolve("fixture.mcpr"), dir.resolve("resume.json")));
        assertEquals(Set.of(1), received);
        rejectThird = false;
        uploader().upload(dir.resolve("fixture.mcpr"), dir.resolve("resume.json"));
        assertEquals(1, posts);
        assertEquals(List.of(1, 3, 3, 2), order);
    }
    @Test void neverReturnsAnUnverifiedDownloadLink() {
        neverComplete = true;
        assertThrows(Exception.class, () -> uploader().upload(dir.resolve("fixture.mcpr"), dir.resolve("resume.json")));
    }
}
