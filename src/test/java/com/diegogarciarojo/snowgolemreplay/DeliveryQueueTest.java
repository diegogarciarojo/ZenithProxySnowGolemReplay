package com.diegogarciarojo.snowgolemreplay;

import com.google.gson.Gson;
import com.zenith.discord.Embed;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryQueueTest {
    @TempDir Path dir;
    Path replay, jobFile;
    DeliveryQueue queue;
    FakeDiscord discord;
    int uploads;
    boolean failUpload;
    static final String LINK = "https://file.kiwi/example#synthetic-key";

    static class FakeDiscord implements DeliveryQueue.DiscordTransport {
        long limit = Long.MAX_VALUE;
        int sends, edits;
        boolean offline, missing, failEdit;
        String currentChannel = "channel-1", editedChannel;
        Embed sent, edited;
        Path attachment;
        public String defaultChannel() { return currentChannel; }
        public long attachmentLimit(String id) throws IOException {
            if (offline) throw new IOException("offline");
            return limit;
        }
        public String send(String id, Embed embed, Path file) throws IOException {
            if (offline) throw new IOException("offline");
            assertTrue(Embed.validateEmbed(embed));
            assertNotNull(embed.toJDAEmbed());
            sends++; sent = embed; attachment = file;
            return "message-" + sends;
        }
        public boolean edit(String channel, String message, Embed embed) throws IOException {
            if (offline || failEdit) throw new IOException("failed edit");
            edits++; edited = embed; editedChannel = channel;
            return !missing;
        }
    }
    @BeforeEach void setup() throws Exception {
        SnowGolemReplayPlugin.CONFIG = new GolemReplayConfig();
        SnowGolemReplayPlugin.LOG = ComponentLogger.logger("DeliveryTest");
        replay = Files.write(dir.resolve("fixture.mcpr"), new byte[10]);
        jobFile = dir.resolve("fixture.mcpr.delivery.json");
        discord = new FakeDiscord();
        queue = newQueue();
        queue.enqueue(replay, List.of(RollingWindowsTest.event("ENTITY_STATUS_DEATH")), "Post-event interval completed");
    }
    DeliveryQueue newQueue() {
        return new DeliveryQueue(dir, discord, (file, resume) -> {
            uploads++;
            if (failUpload) throw new IOException("synthetic error");
            return LINK;
        }, false);
    }
    @AfterEach void close() { queue.shutdown(); }
    DeliveryQueue.Job job() throws Exception { return new Gson().fromJson(Files.readString(jobFile), DeliveryQueue.Job.class); }
    void allowRetry() throws Exception { var job = job(); job.nextAttempt = 0; KiwiUploader.writeJson(jobFile, job); }
    static String field(Embed embed, String name) {
        return embed.fields().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow().value();
    }
    @Test void sendsNativeEmbedAndAttachmentThenAddsVerifiedLinkToSameMessage() throws Exception {
        queue.deliver(jobFile);
        assertEquals(1, discord.sends); assertEquals(1, discord.edits); assertEquals(1, uploads);
        assertEquals(replay, discord.attachment);
        assertEquals("Snow Golem Death Detected!", discord.sent.title());
        assertEquals(com.zenith.Globals.CONFIG.theme.error.color(), discord.sent.color());
        assertTrue(discord.sent.fields().get(0).inline()); assertTrue(discord.sent.fields().get(1).inline());
        assertFalse(discord.sent.fields().stream().anyMatch(f -> f.name().contains("kiwi")));
        assertTrue(field(discord.edited, "File.kiwi Download Link").contains(LINK));
        assertEquals("message-1", job().messageId);
        assertTrue(job().attachmentDone && job().kiwiDone && job().linkDone);
        queue.deliver(jobFile);
        assertEquals(1, uploads); assertEquals(1, discord.sends);
    }
    @Test void discordOutageDoesNotBlockKiwiAndRetryUsesSavedUpload() throws Exception {
        discord.offline = true;
        queue.deliver(jobFile);
        assertTrue(job().kiwiDone); assertFalse(job().attachmentDone); assertFalse(job().linkDone);
        discord.offline = false;
        allowRetry(); queue.deliver(jobFile);
        assertEquals(1, uploads); assertEquals(1, discord.sends); assertEquals(0, discord.edits);
        assertTrue(field(discord.sent, "File.kiwi Download Link").contains(LINK));
        assertEquals("", job().lastError);
    }
    @Test void resumesAfterRestartAndEditsOriginalChannelEvenIfSettingChanged() throws Exception {
        failUpload = true; queue.deliver(jobFile);
        assertTrue(job().attachmentDone); assertFalse(job().kiwiDone);
        queue.shutdown(); queue = newQueue(); failUpload = false;
        discord.currentChannel = "channel-2";
        allowRetry(); queue.deliver(jobFile);
        assertEquals(1, discord.sends); assertEquals("channel-1", discord.editedChannel);
        assertTrue(job().linkDone);
    }
    @Test void editFailureRetriesWithoutUploadingFileAgain() throws Exception {
        discord.failEdit = true; queue.deliver(jobFile);
        assertTrue(job().attachmentDone && job().kiwiDone); assertFalse(job().linkDone);
        discord.failEdit = false; allowRetry(); queue.deliver(jobFile);
        assertEquals(1, uploads); assertEquals(1, discord.sends); assertTrue(job().linkDone);
    }
    @Test void deletedMessageGetsReplacementEmbed() throws Exception {
        discord.missing = true; queue.deliver(jobFile);
        assertEquals(2, discord.sends); assertTrue(job().linkDone);
        assertTrue(field(discord.sent, "File.kiwi Download Link").contains(LINK));
    }
    @Test void oversizedReplayStillGetsEmbedAndVerifiedLink() throws Exception {
        discord.limit = 5; queue.deliver(jobFile);
        assertNull(discord.attachment); assertTrue(job().attachmentDone && job().linkDone);
        assertTrue(field(discord.edited, "Discord Attachment").contains("limit"));
    }
    @Test void oldTextOnlyJobRecoversIncidentReport() throws Exception {
        Files.writeString(jobFile, "{\"filename\":\"fixture.mcpr\",\"summary\":\"old text\"}");
        KiwiUploader.writeJson(dir.resolve("fixture.mcpr.incident.json"),
            new ReplayFiles.Report("snow-golem-replay/1", "Post-event interval completed", List.of(RollingWindowsTest.event("HEALTH_ZERO"))));
        queue.deliver(jobFile);
        assertEquals("Snow Golem Death Detected!", discord.sent.title());
        assertNotNull(job().incidents); assertTrue(job().linkDone);
    }
    @Test void legacyJobWithoutReportStillGetsAnEmbed() throws Exception {
        Files.writeString(jobFile, "{\"filename\":\"fixture.mcpr\",\"summary\":\"old text\"}");
        queue.deliver(jobFile);
        assertEquals("A saved replay is ready for delivery. Incident details are unavailable.", discord.sent.description()); assertTrue(job().linkDone);
    }
    @Test void testAndClipDoNotInventADeathOrCause() {
        for (String kind : List.of("MANUAL_TEST", "MANUAL_CLIP")) {
            var embed = DiscordMessages.replay("test.mcpr", List.of(RollingWindowsTest.event(kind)), "Post-event interval completed", "", "", "");
            assertFalse(embed.title().contains("Death Detected"));
            assertTrue(embed.description().contains("Deaths detected: 0"));
            assertFalse(embed.fields().stream().anyMatch(f -> f.name().equals("Cause of Death")));
        }
    }
    @Test void damageIsEvidenceRatherThanAnAssertedFinalCauseAndBurstFitsDiscord() {
        var base = RollingWindowsTest.event("HEALTH_ZERO");
        var damage = new Incident.Damage(base.timeUtc(), 750, "minecraft:mob_projectile", 1, 2,
            "SHULKER uuid=example", 3, "SHULKER_BULLET uuid=example", "null");
        var incident = new Incident(base.id(), base.timeUtc(), base.timeLocal(), base.uuid(), base.entityId(), base.dimension(),
            base.x(), base.y(), base.z(), base.confirmation(), base.replayTimestampMs(), base.availablePreMs(),
            base.requestedPreSeconds(), true, List.of(damage), "test");
        var embed = DiscordMessages.replay("test.mcpr", Collections.nCopies(100, incident), "Post-event interval completed", LINK, "", "");
        assertTrue(field(embed, "Cause of Death").contains("Final cause unconfirmed"));
        assertTrue(field(embed, "Cause of Death").contains("SHULKER_BULLET"));
        assertTrue(Embed.validateEmbed(embed)); assertNotNull(embed.toJDAEmbed());
    }
}
