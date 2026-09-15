package com.diegogarciarojo.snowgolemreplay;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayFilesTest {
    @TempDir Path dir;
    @Test void testRecordingIsExactlyOneMinuteAndIncludesDeathMarkerAndReport() throws Exception {
        Path source = dir.resolve("source.mcpr"), output = dir.resolve("test.mcpr");
        try (var zip = new ZipOutputStream(Files.newOutputStream(source))) {
            zip.putNextEntry(new ZipEntry("metaData.json")); zip.write("{\"duration\":65000,\"protocol\":769}".getBytes()); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("recording.tmcpr"));
            var data = new DataOutputStream(zip);
            for (int t : new int[]{0, 1000, 999, 59000, 60000, 60001}) { data.writeInt(t); data.writeInt(1); data.writeByte(42); }
            zip.closeEntry();
        }
        var death = new Incident("death", "utc", "local", "uuid", 1, "world", 1, 2, 3,
            "ENTITY_STATUS_DEATH", 59000, 59000, 60, false, List.of(), "test");
        ReplayFiles.export(source, output, List.of(death), "Manual 60-second test completed", 60000);
        try (var zip = new ZipFile(output.toFile())) {
            var metadata = JsonParser.parseReader(new InputStreamReader(zip.getInputStream(zip.getEntry("metaData.json")))).getAsJsonObject();
            assertEquals(60000, metadata.get("duration").getAsInt());
            var markers = JsonParser.parseReader(new InputStreamReader(zip.getInputStream(zip.getEntry("markers.json")))).getAsJsonArray();
            var marker = markers.get(0).getAsJsonObject();
            assertEquals(59000, marker.get("realTimestamp").getAsInt());
            assertEquals("Snow golem death: ENTITY_STATUS_DEATH", marker.getAsJsonObject("value").get("name").getAsString());
            assertNotNull(marker.getAsJsonObject("value").getAsJsonObject("position"));
            assertNotNull(zip.getEntry("golem-incident.json"));
            try (var data = new DataInputStream(zip.getInputStream(zip.getEntry("recording.tmcpr")))) {
                for (int expected : new int[]{0, 1000, 1000, 59000, 60000}) {
                    assertEquals(expected, data.readInt()); assertEquals(1, data.readInt()); assertEquals(42, data.readByte());
                }
                assertEquals(-1, data.read());
            }
        }
        assertTrue(Files.exists(dir.resolve("test.mcpr.incident.json")));
    }
    @Test void refusesIncompleteArchive() throws Exception {
        Path source = dir.resolve("broken.mcpr");
        try (var zip = new ZipOutputStream(Files.newOutputStream(source))) {
            zip.putNextEntry(new ZipEntry("recording.tmcpr")); zip.write(new byte[]{1}); zip.closeEntry();
        }
        assertThrows(IOException.class, () -> ReplayFiles.export(source, dir.resolve("out.mcpr"), List.of(), "test", 60000));
        assertFalse(Files.exists(dir.resolve("out.mcpr")));
    }
}
