package com.diegogarciarojo.snowgolemreplay;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.zip.*;

final class ReplayFiles {
    record Report(String format, String ending, List<Incident> incidents) {}
    static void export(Path input, Path output, List<Incident> incidents, String ending, long duration) throws IOException {
        Path partial = output.resolveSibling(output.getFileName() + ".partial");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Report report = new Report("snow-golem-replay/1", ending, List.copyOf(incidents));
        var markers = new JsonArray();
        for (Incident event : incidents) {
            // ReplayMod/ReplayStudio expects this envelope; flat markers make
            // AbstractReplayFile.getMarkers() dereference value=null.
            var marker = new JsonObject();
            marker.addProperty("realTimestamp", event.replayTimestampMs());
            var value = new JsonObject();
            value.addProperty("name", "MANUAL_TEST".equals(event.confirmation()) ? "60-second test start" : "Snow golem death: " + event.confirmation());
            var position = new JsonObject();
            position.addProperty("x", event.x()); position.addProperty("y", event.y() + 2); position.addProperty("z", event.z());
            position.addProperty("yaw", 0); position.addProperty("pitch", 30); position.addProperty("roll", 0);
            value.add("position", position);
            marker.add("value", value);
            markers.add(marker);
        }
        try (ZipFile source = new ZipFile(input.toFile());
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(partial)))) {
            if (source.getEntry("metaData.json") == null || source.getEntry("recording.tmcpr") == null)
                throw new IOException("Incomplete source replay");
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.getName().equals("markers.json") || entry.getName().equals("golem-incident.json")) continue;
                zip.putNextEntry(new ZipEntry(entry.getName()));
                try (var in = source.getInputStream(entry)) {
                    if (entry.getName().equals("metaData.json")) {
                        var metadata = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                        metadata.addProperty("duration", duration);
                        zip.write(gson.toJson(metadata).getBytes(StandardCharsets.UTF_8));
                    } else if (entry.getName().equals("recording.tmcpr")) {
                        var packets = new DataInputStream(in);
                        var target = new DataOutputStream(zip);
                        int previous = 0;
                        while (true) {
                            int time;
                            try { time = packets.readInt(); } catch (EOFException e) { break; }
                            int size = packets.readInt();
                            if (size < 1 || size > 64 * 1024 * 1024) throw new IOException("Invalid replay packet length");
                            byte[] data = packets.readNBytes(size);
                            if (data.length != size) throw new EOFException("Truncated replay packet");
                            if (time > duration) continue;
                            // Async self-player updates can have a slightly earlier arrival timestamp.
                            previous = Math.max(previous, time);
                            target.writeInt(previous); target.writeInt(size); target.write(data);
                        }
                    } else in.transferTo(zip);
                }
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("markers.json")); zip.write(gson.toJson(markers).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("golem-incident.json")); zip.write(gson.toJson(report).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING);
        KiwiUploader.writeJson(output.resolveSibling(output.getFileName() + ".incident.json"), report);
    }
}
