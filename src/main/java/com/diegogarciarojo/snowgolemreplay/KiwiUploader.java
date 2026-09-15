package com.diegogarciarojo.snowgolemreplay;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** One chunk in flight. Completion is checked before a share URL is returned. */
final class KiwiUploader {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final URI api;
    private final HttpClient http;
    KiwiUploader() { this(URI.create("https://api.file.kiwi")); }
    KiwiUploader(URI api) {
        this.api = api;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    String upload(Path file, Path resume) throws Exception {
        JsonObject state;
        if (Files.exists(resume)) {
            state = JsonParser.parseString(Files.readString(resume)).getAsJsonObject();
            if (state.get("size").getAsLong() != Files.size(file)) throw new IOException("Replay changed since upload registration");
            if (System.currentTimeMillis() - state.get("created").getAsLong() > Duration.ofHours(35).toMillis()) {
                // Old signed URLs expire; register a fresh folder and retain the local replay.
                state = create(file);
                writeJson(resume, state);
            }
        } else {
            state = create(file);
            writeJson(resume, state);
        }
        byte[] key = Base64.getUrlDecoder().decode(state.get("secretKey").getAsString());
        JsonObject response = state.getAsJsonObject("response");
        JsonObject meta = response.getAsJsonArray("files").get(0).getAsJsonObject();
        int chunks = meta.get("chunks").getAsInt();
        int chunkSize = meta.get("chunkSize").getAsInt();
        long size = Files.size(file);
        if (chunkSize <= 0 || chunkSize > 64 * 1024 * 1024 || chunks != (size + chunkSize - 1) / chunkSize)
            throw new IOException("Unexpected file.kiwi chunk layout");
        JsonObject urls = meta.getAsJsonObject("uploadUrls");
        if (urls.getAsJsonArray("signatures").size() != chunks) throw new IOException("Missing chunk signatures");
        URI check = api.resolve("/v2/folders/" + enc(response.get("folderId").getAsString()) + "/files/"
            + enc(meta.get("fileId").getAsString()) + "/upload-status?uploadAuth=" + enc(response.get("uploadAuth").getAsString()));
        for (int pass = 0; pass < 3; pass++) {
            JsonObject status = requestJson(HttpRequest.newBuilder(check).timeout(Duration.ofSeconds(45)).GET().build());
            if (Boolean.TRUE.equals(status.has("complete") ? status.get("complete").getAsBoolean() : null)) return link(response, key);
            if (!status.has("missing")) throw new IOException("Upload verification omitted missing chunks");
            Set<Integer> missing = new HashSet<>();
            for (JsonElement item : status.getAsJsonArray("missing")) {
                int number = item.getAsInt();
                if (number < 1 || number > chunks) throw new IOException("Invalid missing chunk index");
                missing.add(number);
            }
            try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
                for (int number : chunkOrder(chunks)) {
                    if (!missing.contains(number)) continue;
                    long start = (long)(number - 1) * chunkSize;
                    byte[] plain = new byte[(int)Math.min(chunkSize, size - start)];
                    input.seek(start);
                    input.readFully(plain);
                    byte[] encrypted = KiwiCrypto.encrypt(plain, key);
                    String url = urls.get("head").getAsString() + urls.get("path").getAsString() + "/"
                        + String.format(Locale.ROOT, "%05d", number) + "?" + urls.get("tail").getAsString()
                        + "&X-Amz-Signature=" + urls.getAsJsonArray("signatures").get(number - 1).getAsString();
                    URI target = URI.create(url);
                    if (!"https".equalsIgnoreCase(target.getScheme()) && !isLocalTest(target)) throw new IOException("Insecure upload URL");
                    var builder = HttpRequest.newBuilder(target).timeout(Duration.ofMinutes(3))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(encrypted));
                    for (var header : urls.getAsJsonObject("headers").entrySet()) builder.header(header.getKey(), header.getValue().getAsString());
                    request(builder.build());
                }
            }
        }
        JsonObject status = requestJson(HttpRequest.newBuilder(check).timeout(Duration.ofSeconds(45)).GET().build());
        if (!status.has("complete") || !status.get("complete").getAsBoolean()) throw new IOException("file.kiwi has not confirmed all chunks");
        return link(response, key);
    }

    private boolean isLocalTest(URI target) {
        return "127.0.0.1".equals(api.getHost()) && "127.0.0.1".equals(target.getHost());
    }
    private JsonObject create(Path file) throws Exception {
        if (Files.size(file) <= 0) throw new IOException("Empty replay");
        byte[] key = KiwiCrypto.randomKey();
        String fragment = KiwiCrypto.fragment(key);
        var body = new JsonObject();
        body.addProperty("title", "Snow golem replay");
        body.addProperty("mode", "send");
        var encryption = new JsonObject();
        encryption.addProperty("ske", KiwiCrypto.encryptedText(fragment, key));
        body.add("encryption", encryption);
        var files = new JsonArray();
        var entry = new JsonObject();
        entry.addProperty("filename", KiwiCrypto.encryptedText(file.getFileName().toString(), key));
        entry.addProperty("filesize", Files.size(file));
        entry.addProperty("mimetype", "application/octet-stream");
        files.add(entry);
        body.add("files", files);
        // Do not blindly retry POST: a lost response could otherwise create duplicate folders.
        var result = http.send(HttpRequest.newBuilder(api.resolve("/v2/folders")).timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build(),
            HttpResponse.BodyHandlers.ofString());
        if (result.statusCode() / 100 != 2) throw new IOException("file.kiwi registration HTTP " + result.statusCode());
        var state = new JsonObject();
        state.addProperty("created", System.currentTimeMillis());
        state.addProperty("size", Files.size(file));
        state.addProperty("secretKey", fragment);
        state.add("response", JsonParser.parseString(result.body()).getAsJsonObject());
        return state;
    }
    private static String link(JsonObject response, byte[] key) throws IOException {
        String base = response.has("folderUrlBase") ? response.get("folderUrlBase").getAsString() : response.get("folderUrl").getAsString();
        URI uri = URI.create(base);
        if (!"https".equals(uri.getScheme()) || !"file.kiwi".equals(uri.getHost())) throw new IOException("Invalid share URL");
        return base.split("#", 2)[0] + "#" + KiwiCrypto.fragment(key);
    }
    private JsonObject requestJson(HttpRequest req) throws Exception { return JsonParser.parseString(request(req)).getAsJsonObject(); }
    private String request(HttpRequest req) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpResponse<String> r;
            try { r = http.send(req, HttpResponse.BodyHandlers.ofString()); }
            catch (IOException e) {
                if (attempt == 2) throw new IOException("file.kiwi network request failed");
                Thread.sleep(1000L << attempt);
                continue;
            }
            if (r.statusCode() / 100 == 2) return r.body();
            if (attempt == 2 || (r.statusCode() != 429 && r.statusCode() < 500)) throw new IOException("file.kiwi HTTP " + r.statusCode());
            long delay = r.headers().firstValue("Retry-After").flatMap(s -> {
                try { return Optional.of(Long.parseLong(s) * 1000); } catch (NumberFormatException e) { return Optional.empty(); }
            }).orElse(1000L << attempt);
            Thread.sleep(Math.min(60000, Math.max(1000, delay)));
        }
        throw new IOException("file.kiwi request failed");
    }
    static List<Integer> chunkOrder(int chunks) {
        var result = new ArrayList<Integer>();
        if (chunks > 0) result.add(1);
        if (chunks > 1) result.add(chunks);
        for (int n = 2; n < chunks; n++) result.add(n);
        return result;
    }
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static void writeJson(Path destination, Object data) throws IOException {
        Path temp = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.writeString(temp, JSON.toJson(data));
        try { Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING); }
    }
}
