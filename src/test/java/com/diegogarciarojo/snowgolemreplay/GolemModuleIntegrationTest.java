package com.diegogarciarojo.snowgolemreplay;

import com.google.gson.*;
import com.viaversion.nbt.io.MNBTIO;
import com.viaversion.nbt.tag.CompoundTag;
import com.zenith.Globals;
import com.zenith.Proxy;
import com.zenith.cache.data.entity.EntityStandard;
import com.zenith.module.impl.ReplayMod;
import com.zenith.network.client.ClientSession;
import com.zenith.util.ReplayReader;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EntityEvent;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.FloatEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

/** Uses released ZenithProxy/MCProtocolLib, a cached synthetic world and no network connection. */
class GolemModuleIntegrationTest {
    @TempDir Path root;
    GolemReplayModule module;
    FakeClient client;
    static class FakeClient extends ClientSession {
        FakeClient(MinecraftProtocol protocol) { super("127.0.0.1", 25565, "", protocol, null); }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isOnline() { return true; }
    }
    @BeforeEach void setup() throws Exception {
        SnowGolemReplayPlugin.CONFIG = new GolemReplayConfig();
        SnowGolemReplayPlugin.CONFIG.discordEnabled = false;
        SnowGolemReplayPlugin.CONFIG.kiwiEnabled = false;
        SnowGolemReplayPlugin.LOG = ComponentLogger.logger("GolemIntegration");
        Globals.CONFIG.authentication.username = "SyntheticReplayTest";
        if (Globals.MODULE.get(ReplayMod.class) == null) Globals.MODULE.registerModule(new ReplayMod());
        var profile = new GameProfile(UUID.fromString("12345678-1234-1234-1234-123456789abc"), "SyntheticReplayTest");
        var protocol = new MinecraftProtocol(profile, "");
        protocol.setUseDefaultListeners(false); protocol.setTargetState(ProtocolState.GAME);
        protocol.setInboundState(ProtocolState.GAME); protocol.setOutboundState(ProtocolState.GAME);
        client = new FakeClient(protocol);
        field(Proxy.class, "client").set(Proxy.getInstance(), client);
        Globals.CACHE.getProfileCache().setProfile(profile);
        Globals.CACHE.getPlayerCache().setGameMode(GameMode.CREATIVE);
        Globals.CACHE.getPlayerCache().getThePlayer().setEntityId(99).setUuid(profile.getId()).setX(0).setY(64).setZ(0);
        Globals.CACHE.getChunkCache().setWorldName(Key.key("minecraft:overworld"));
        Globals.CACHE.getChunkCache().setWorldNames(new ArrayList<>(List.of(Key.key("minecraft:overworld"))));
        Globals.CACHE.getChunkCache().setServerViewDistance(2).setServerSimulationDistance(2);
        for (var tag : MinecraftProtocol.loadNetworkCodec().getValue().values()) {
            var registry = (CompoundTag)tag;
            var entries = new ArrayList<RegistryEntry>();
            for (var entry : registry.getListTag("value", CompoundTag.class))
                entries.add(entry.getInt("id"), new RegistryEntry(entry.getString("name"), MNBTIO.write(entry.get("element"), false)));
            Globals.CACHE.getRegistriesCache().initialize(registry.getString("type"), entries);
        }
        Globals.CACHE.getEntityCache().getEntities().clear();
        add(1, EntityType.SNOW_GOLEM); add(2, EntityType.SHULKER); add(3, EntityType.SNOW_GOLEM);
        module = new GolemReplayModule(root); module.enable(); tick();
        assertTrue(module.status().contains("active windows=1"), module.status());
    }
    void add(int id, EntityType type) {
        var e = new EntityStandard(); e.setEntityId(id).setUuid(UUID.randomUUID()).setEntityType(type).setX(1).setY(64).setZ(1);
        e.setObjectData(new org.geysermc.mcprotocollib.protocol.data.game.entity.object.GenericObjectData(0));
        Globals.CACHE.getEntityCache().add(e);
    }
    static Field field(Class<?> c, String name) throws Exception { var f = c.getDeclaredField(name); f.setAccessible(true); return f; }
    void tick() throws Exception { var m = GolemReplayModule.class.getDeclaredMethod("tick"); m.setAccessible(true); m.invoke(module); }
    void receive(MinecraftPacket packet) throws Exception {
        module.getClientPacketHandlerCodec().handleInbound(packet, client);
        client.getClientEventLoop().submit(() -> {}).get(10, TimeUnit.SECONDS);
    }
    @AfterEach void cleanup() throws Exception {
        if (module != null) { module.disable(); module.shutdown(); }
        if (client != null) client.getClientEventLoop().shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        field(Proxy.class, "client").set(Proxy.getInstance(), null);
        Globals.CACHE.getEntityCache().getEntities().clear();
    }
    @Test void realPacketsDetectGolemDeathOnlyOnceAndProduceReadableReplay() throws Exception {
        receive(new ClientboundRemoveEntitiesPacket(new int[]{3}));
        Globals.CACHE.getEntityCache().getEntities().remove(3);
        receive(new ClientboundEntityEventPacket(2, EntityEvent.LIVING_DEATH)); // shulker is not a golem
        receive(new ClientboundEntityEventPacket(1, EntityEvent.LIVING_HURT)); // hurt is not death
        assertEquals(0, incidentCount());
        receive(new ClientboundDamageEventPacket(1, 0, 2, 2, null));
        receive(new ClientboundEntityEventPacket(1, EntityEvent.LIVING_DEATH));
        receive(new ClientboundSetEntityDataPacket(1, List.of(new FloatEntityMetadata(9, MetadataTypes.FLOAT, 0))));
        assertEquals(1, incidentCount());
        module.disable(); module.shutdown();
        try (var paths = Files.list(root.resolve("incidents"))) {
            var replay = paths.filter(p -> p.toString().endsWith(".mcpr")).findFirst().orElseThrow();
            Path log = root.resolve("decoded.txt");
            new ReplayReader(replay.toFile(), log.toFile()).read();
            String decoded = Files.readString(log);
            assertTrue(decoded.contains("LIVING_DEATH"));
            assertTrue(decoded.contains("ClientboundLoginPacket"));
            try (var zip = new ZipFile(replay.toFile())) {
                var report = JsonParser.parseReader(new java.io.InputStreamReader(zip.getInputStream(zip.getEntry("golem-incident.json")))).getAsJsonObject();
                var incident = report.getAsJsonArray("incidents").get(0).getAsJsonObject();
                assertEquals(1, incident.getAsJsonArray("recentDamage").size());
                assertFalse(incident.get("completePreHistory").getAsBoolean());
            }
        }
    }
    int incidentCount() throws Exception {
        var buffer = (RollingWindows<?>)field(GolemReplayModule.class, "buffer").get(module);
        return buffer.windows.stream().mapToInt(w -> w.incidents.size()).sum();
    }
    @Test void manualTestContainsDeathAndNoDoubleCountAndStopsAtOneMinute() throws Exception {
        module.startTest(); client.getClientEventLoop().submit(() -> {}).get(10, TimeUnit.SECONDS);
        var test = (RollingWindows.Window<?>)field(GolemReplayModule.class, "testWindow").get(module);
        assertNotNull(test); assertEquals(TimeUnit.SECONDS.toNanos(60), test.deadline - test.start);
        receive(new ClientboundSetEntityDataPacket(1, List.of(new FloatEntityMetadata(9, MetadataTypes.FLOAT, 0))));
        receive(new ClientboundEntityEventPacket(1, EntityEvent.LIVING_DEATH));
        assertEquals(2, test.incidents.size()); // start marker + one death
        assertEquals("MANUAL_TEST", test.incidents.getFirst().confirmation());
        // Deterministic deadline advancement; real packet encoding is unchanged.
        test.deadline = System.nanoTime() - 1;
        tick(); module.disable(); module.shutdown();
        try (var files = Files.list(root.resolve("incidents"))) {
            for (var replay : files.filter(p -> p.toString().endsWith(".mcpr")).toList()) {
                try (var zip = new ZipFile(replay.toFile())) {
                    var report = JsonParser.parseReader(new java.io.InputStreamReader(zip.getInputStream(zip.getEntry("golem-incident.json")))).getAsJsonObject();
                    if (!report.get("ending").getAsString().startsWith("Manual")) continue;
                    var metadata = JsonParser.parseReader(new java.io.InputStreamReader(zip.getInputStream(zip.getEntry("metaData.json")))).getAsJsonObject();
                    assertEquals(60000, metadata.get("duration").getAsInt());
                    assertEquals(2, report.getAsJsonArray("incidents").size());
                    return;
                }
            }
        }
        fail("Manual test replay missing");
    }
    @Test void discordConfigurationCommandsUseRealBrigadierAndValidateBounds() throws Exception {
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<com.zenith.command.api.CommandContext>();
        dispatcher.register(new GolemReplayCommand(module).register());
        var source = new com.zenith.command.api.CommandSource() {
            public String name() { return "Discord integration fixture"; }
            public boolean validateAccountOwner(com.zenith.command.api.CommandContext c) { return true; }
            public void logEmbed(com.zenith.command.api.CommandContext c, com.zenith.discord.Embed embed) {}
        };
        for (String command : List.of("golemreplay buffer 120", "golemreplay post 20", "golemreplay checkpoint 30",
            "golemreplay channel 123456789", "golemreplay minFreeDisk 512", "golemreplay maxBufferDisk 1024")) {
            assertEquals(1, dispatcher.execute(command, com.zenith.command.api.CommandContext.create(command, source)));
        }
        var config = SnowGolemReplayPlugin.CONFIG;
        assertEquals(120, config.preSeconds); assertEquals(20, config.postSeconds); assertEquals(30, config.checkpointSeconds);
        assertEquals("123456789", config.discordChannelId); assertEquals(512, config.minFreeDiskMiB); assertEquals(1024, config.maxBufferMiB);
        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class, () -> dispatcher.execute("golemreplay buffer 0",
            com.zenith.command.api.CommandContext.create("golemreplay buffer 0", source)));
        assertEquals(120, config.preSeconds);
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "golem.realTimeTest", matches = "true")
    void realTimeMinuteBufferSurvivesFourRotationsAndKeepsPreDeathDamage() throws Exception {
        long start = System.nanoTime();
        boolean early = false, killed = false;
        while (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 77) {
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
            tick();
            if (seconds >= 5 && !early) { receive(new ClientboundEntityEventPacket(1, EntityEvent.LIVING_HURT)); early = true; }
            if (seconds >= 65 && !killed) { receive(new ClientboundEntityEventPacket(1, EntityEvent.LIVING_DEATH)); killed = true; }
            Thread.sleep(50);
        }
        module.disable(); module.shutdown();
        try (var files = Files.list(root.resolve("incidents"))) {
            var replay = files.filter(p -> p.toString().endsWith(".mcpr")).findFirst().orElseThrow();
            Path packetLog = root.resolve("realtime-packets.txt");
            new ReplayReader(replay.toFile(), packetLog.toFile()).read();
            String decoded = Files.readString(packetLog);
            assertTrue(decoded.indexOf("LIVING_HURT") < decoded.indexOf("LIVING_DEATH"));
            assertTrue(decoded.contains("LIVING_HURT"));
            try (var zip = new ZipFile(replay.toFile())) {
                var report = JsonParser.parseReader(new java.io.InputStreamReader(zip.getInputStream(zip.getEntry("golem-incident.json")))).getAsJsonObject();
                var incident = report.getAsJsonArray("incidents").get(0).getAsJsonObject();
                assertTrue(incident.get("completePreHistory").getAsBoolean());
                assertTrue(incident.get("availablePreMs").getAsLong() >= 60000);
            }
            Path evidence = Path.of(System.getProperty("golem.evidenceDir", "real-time-evidence"));
            Files.createDirectories(evidence);
            Files.copy(replay, evidence.resolve("synthetic-77-second-test.mcpr"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(packetLog, evidence.resolve("synthetic-77-second-test-packets.txt"), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
