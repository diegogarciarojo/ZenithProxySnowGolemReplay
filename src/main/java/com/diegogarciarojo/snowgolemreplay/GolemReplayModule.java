package com.diegogarciarojo.snowgolemreplay;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.cache.data.entity.Entity;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientTickEvent;
import com.zenith.event.queue.QueueStartEvent;
import com.zenith.feature.player.World;
import com.zenith.mc.damage_type.DamageTypeRegistry;
import com.zenith.module.api.Module;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.PacketHandlerCodec;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.spawn.ClientboundAddEntityPacket;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.zenith.Globals.CACHE;
import static com.diegogarciarojo.snowgolemreplay.SnowGolemReplayPlugin.*;

public final class GolemReplayModule extends Module {
    static final Path ROOT = Path.of("replays", "snow-golem");
    private final Path bufferDirectory;
    private final Path incidentsDirectory;
    private final RollingWindows<BufferedReplayRecording> buffer = new RollingWindows<>();
    private final Map<Integer, Tracked> tracked = new HashMap<>();
    private final ExecutorService finalizer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "golem-replay-finalizer"); t.setDaemon(true); return t;
    });
    private final DeliveryQueue delivery;
    private ClientSession session;
    private long lastCheckpoint, lastDiskCheck, pauseUntil;
    private String lastProblem = "";
    private volatile boolean stopped;
    private RollingWindows.Window<BufferedReplayRecording> testWindow;
    public GolemReplayModule() { this(ROOT); }
    GolemReplayModule(Path root) {
        bufferDirectory = root.resolve("buffer");
        incidentsDirectory = root.resolve("incidents");
        delivery = new DeliveryQueue(incidentsDirectory);
    }
    private record DamageAt(long nano, Incident.Damage data) {}
    private static final class Tracked {
        final int id; final UUID uuid;
        double x, y, z;
        boolean dead;
        final ArrayDeque<DamageAt> damage = new ArrayDeque<>();
        Tracked(Entity e) { id = e.getEntityId(); uuid = e.getUuid(); update(e); }
        void update(Entity e) { x = e.getX(); y = e.getY(); z = e.getZ(); }
    }

    @Override public boolean enabledSetting() { return CONFIG.enabled; }
    @Override public List<EventConsumer<?>> registerEvents() {
        return List.of(EventConsumer.of(ClientTickEvent.class, e -> tick()),
            EventConsumer.of(ClientDisconnectEvent.class, e -> reset("Disconnected: post-event recording interrupted")),
            EventConsumer.of(QueueStartEvent.class, e -> reset("Returned to queue: post-event recording interrupted")));
    }
    @Override public void onEnable() { CONFIG.validate(); }
    @Override public void onDisable() { reset("Module disabled: post-event recording interrupted"); }

    private synchronized void tick() {
        if (stopped || !isEnabled()) return;
        ClientSession current = Proxy.getInstance().getClient();
        if (current == null || !current.isOnline() || current.isInQueue()) return;
        if (session != current) { reset("New connection"); session = current; }
        long now = System.nanoTime();
        if (now < pauseUntil) {
            if (testWindow != null) {
                if (!testWindow.value.healthy()) {
                    lastProblem = "Test writer failed: " + testWindow.value.failureDescription();
                    reset("Recording error: test interrupted");
                } else if (now >= testWindow.deadline) {
                    finish(testWindow, "Manual 60-second test completed"); testWindow = null;
                }
                for (Entity entity : CACHE.getEntityCache().getEntities().values()) track(entity);
            }
            return;
        }
        try {
            Files.createDirectories(bufferDirectory);
            Files.createDirectories(incidentsDirectory);
            if (now - lastDiskCheck >= TimeUnit.SECONDS.toNanos(1)) {
                lastDiskCheck = now;
                for (var window : buffer.windows) if (!window.value.healthy())
                    throw new IOException("Replay writer failed: " + window.value.failureDescription());
                if (testWindow != null && !testWindow.value.healthy())
                    throw new IOException("Test writer failed: " + testWindow.value.failureDescription());
                long bytes;
                try (var paths = Files.walk(bufferDirectory)) {
                    bytes = paths.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
                }
                checkDisk(bytes);
            }
            if (buffer.windows.isEmpty() || now - lastCheckpoint >= TimeUnit.SECONDS.toNanos(CONFIG.checkpointSeconds)) {
                // Unique directories avoid ReplayRecording's second-resolution filename collisions.
                Path directory = Files.createTempDirectory(bufferDirectory, "window-");
                BufferedReplayRecording recording = new BufferedReplayRecording(directory);
                try { recording.startRecording(); }
                catch (Exception e) { try { recording.close(); } catch (Exception ignored) {} throw e; }
                buffer.add(recording.getStartT(), recording);
                lastCheckpoint = now;
            }
            for (var retired : buffer.retire(now, preNanos())) finish(retired, "Post-event interval completed");
            if (testWindow != null && now >= testWindow.deadline) {
                finish(testWindow, "Manual 60-second test completed");
                testWindow = null;
            }
            for (Entity entity : CACHE.getEntityCache().getEntities().values()) track(entity);
        } catch (Exception e) {
            lastProblem = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.error("Snow golem recording suspended", e);
            reset("Recording error: post-event interval interrupted");
            pauseUntil = now + TimeUnit.SECONDS.toNanos(60);
        }
    }

    private void track(Entity entity) {
        if (entity == null || entity.getEntityType() != EntityType.SNOW_GOLEM) return;
        if (!CONFIG.watchedUuids.isEmpty() && !CONFIG.watchedUuids.contains(entity.getUuid().toString())) return;
        Tracked old = tracked.get(entity.getEntityId());
        if (old == null || !old.uuid.equals(entity.getUuid())) tracked.put(entity.getEntityId(), new Tracked(entity));
        else old.update(entity);
    }

    private synchronized void packet(MinecraftPacket packet, ClientSession client, long time, boolean inbound) {
        if (stopped || !isEnabled() || session != client || buffer.windows.isEmpty() && testWindow == null) return;
        try {
            // Record before detection, so status/health and the actual death animation are included.
            for (var w : buffer.windows) {
                if (inbound) w.value.handleInboundPacket(time, packet, client);
                else w.value.handleOutgoingPacket(time, packet, client);
            }
            if (testWindow != null && time <= testWindow.deadline) {
                if (inbound) testWindow.value.handleInboundPacket(time, packet, client);
                else testWindow.value.handleOutgoingPacket(time, packet, client);
            }
            if (!inbound) return;
            if (packet instanceof ClientboundRespawnPacket) {
                reset("Respawn/dimension change: post-event interval interrupted");
                return;
            }
            if (packet instanceof ClientboundAddEntityPacket p) track(CACHE.getEntityCache().get(p.getEntityId()));
            else if (packet instanceof ClientboundRemoveEntitiesPacket p) {
                // An entity removal is NOT proof of death (unload, teleport, visibility).
                for (int id : p.getEntityIds()) tracked.remove(id);
            } else if (packet instanceof ClientboundDamageEventPacket p) {
                track(CACHE.getEntityCache().get(p.getEntityId()));
                Tracked t = tracked.get(p.getEntityId());
                if (t != null && !t.dead) {
                    var type = DamageTypeRegistry.REGISTRY.get(p.getSourceTypeId());
                    t.damage.addLast(new DamageAt(time, new Incident.Damage(Instant.now().toString(), 0,
                        type == null ? "unknown" : type.name(), p.getSourceTypeId(), p.getSourceCauseId(), describe(p.getSourceCauseId()),
                        p.getSourceDirectId(), describe(p.getSourceDirectId()), String.valueOf(p.getSourcePosition()))));
                    while (t.damage.size() > 128 || (!t.damage.isEmpty() && time - t.damage.getFirst().nano > preNanos())) t.damage.removeFirst();
                }
            } else if (packet instanceof ClientboundEntityEventPacket p) {
                if (p.getEvent().name().equals("LIVING_DEATH")) death(p.getEntityId(), time, "ENTITY_STATUS_DEATH");
            } else if (packet instanceof ClientboundSetEntityDataPacket p) {
                for (var data : p.getMetadata()) {
                    // Minecraft 1.21.4 LivingEntity health=9; Entity pose=6. Version is pinned.
                    if (data.getId() == 9 && data.getType() == MetadataTypes.FLOAT && data.getValue() instanceof Float f && f <= 0)
                        death(p.getEntityId(), time, "HEALTH_ZERO");
                    else if (data.getId() == 6 && data.getType() == MetadataTypes.POSE && data.getValue() == Pose.DYING)
                        death(p.getEntityId(), time, "POSE_DYING");
                }
            }
        } catch (Exception e) { LOG.error("Snow golem packet processing failed", e); }
    }

    private String describe(int id) {
        if (id < 0) return "not supplied";
        Entity e = CACHE.getEntityCache().get(id);
        return e == null ? "entity not in cache" : e.getEntityType().name() + " uuid=" + e.getUuid();
    }
    private void death(int id, long time, String confirmation) {
        track(CACHE.getEntityCache().get(id));
        Tracked t = tracked.get(id);
        if (t == null || t.dead) return;
        t.dead = true;
        capture(t, time, confirmation);
    }

    private void capture(Tracked t, long time, String confirmation) {
        var w = buffer.select(time, preNanos());
        if (w == null && testWindow == null) { LOG.warn("Death detected before replay buffer was initialized"); return; }
        long available = w == null ? 0 : Math.max(0, TimeUnit.NANOSECONDS.toMillis(time - w.start));
        List<Incident.Damage> damage = t.damage.stream().filter(d -> time - d.nano <= preNanos()).map(d -> {
            var v = d.data;
            return new Incident.Damage(v.timeUtc(), Math.max(0, TimeUnit.NANOSECONDS.toMillis(time - d.nano)), v.type(), v.typeId(),
                v.causeId(), v.cause(), v.directId(), v.direct(), v.sourcePosition());
        }).toList();
        String circumstances = "Environment unavailable";
        try {
            Entity entity = CACHE.getEntityCache().get(t.id);
            var block = World.getBlock((int)Math.floor(t.x), (int)Math.floor(t.y), (int)Math.floor(t.z));
            var flags = entity == null ? null : entity.getMetadataValue(0, MetadataTypes.BYTE, Byte.class);
            circumstances = "Block at feet=" + block.name() + "; raining=" + CACHE.getChunkCache().isRaining()
                + "; onFire=" + (flags == null ? "unknown" : (flags & 1) != 0)
                + ". Observations only; recent damage is not proof of the final cause.";
        } catch (Exception ignored) {}
        String dimension = String.valueOf(CACHE.getChunkCache().getWorldName());
        Incident incident = new Incident(UUID.randomUUID().toString(), Instant.now().toString(), ZonedDateTime.now().toString(),
            t.uuid.toString(), t.id, dimension, t.x, t.y, t.z, confirmation, available, available,
            CONFIG.preSeconds, available >= CONFIG.preSeconds * 1000L, damage, circumstances);
        if (w != null) w.incidents.add(incident);
        if (testWindow != null && time <= testWindow.deadline) {
            long testTime = Math.max(0, TimeUnit.NANOSECONDS.toMillis(time - testWindow.start));
            testWindow.incidents.add(new Incident(incident.id(), incident.timeUtc(), incident.timeLocal(), incident.uuid(),
                incident.entityId(), incident.dimension(), incident.x(), incident.y(), incident.z(), incident.confirmation(),
                testTime, testTime, CONFIG.preSeconds, testTime >= CONFIG.preSeconds * 1000L, incident.recentDamage(), incident.circumstances()));
        }
        // The selected checkpoint advances every rotation, bounding each incident file's life.
        if (w != null) w.deadline = Math.max(w.deadline, time + TimeUnit.SECONDS.toNanos(CONFIG.postSeconds));
        LOG.warn("Snow golem event {} at {}, replay marker {}ms", confirmation, incident.timeUtc(), available);
    }

    private long preNanos() { return TimeUnit.SECONDS.toNanos(CONFIG.preSeconds); }
    private synchronized void reset(String ending) {
        for (var w : buffer.drain()) finish(w, ending);
        if (testWindow != null) { finish(testWindow, ending); testWindow = null; }
        tracked.clear(); session = null; lastCheckpoint = 0;
    }
    private void finish(RollingWindows.Window<BufferedReplayRecording> w, String ending) {
        long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - w.start);
        if (ending.equals("Post-event interval completed")) duration = TimeUnit.NANOSECONDS.toMillis(w.deadline - w.start);
        if (ending.equals("Manual 60-second test completed")) duration = 60_000;
        final long endMs = Math.max(1, duration);
        finalizer.execute(() -> {
            try {
                w.value.close();
                Path file = w.value.getReplayFile().toPath();
                if (w.pinned()) {
                    Path output = incidentsDirectory.resolve("golem-" + w.incidents.getFirst().id() + ".mcpr");
                    ReplayFiles.export(file, output, w.incidents, ending, endMs);
                    delivery.enqueue(output, w.incidents, ending);
                    LOG.info("Saved snow golem evidence: {}", output);
                }
                Files.deleteIfExists(file);
                Files.deleteIfExists(file.getParent());
            } catch (Exception e) { LOG.error("Replay finalization failed; keeping buffer files for recovery", e); }
        });
    }
    public synchronized String status() {
        long history = buffer.windows.isEmpty() ? 0 : TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - buffer.windows.getFirst().start);
        return "Enabled=" + isEnabled() + "; loaded golems=" + tracked.size() + "; history=" + history
            + "s; active windows=" + buffer.windows.size() + "; pre=" + CONFIG.preSeconds + "s; post=" + CONFIG.postSeconds
            + "s; checkpoint=" + CONFIG.checkpointSeconds + "s; test=" + (testWindow != null)
            + "; Discord=" + CONFIG.discordEnabled + "; kiwi=" + CONFIG.kiwiEnabled
            + "; channel=" + (CONFIG.discordChannelId.isEmpty() ? "Zenith default" : CONFIG.discordChannelId)
            + "; minFreeDiskMiB=" + CONFIG.minFreeDiskMiB + "; maxBufferMiB=" + CONFIG.maxBufferMiB
            + "; UUID filter=" + (CONFIG.watchedUuids.isEmpty() ? "all" : CONFIG.watchedUuids)
            + (lastProblem.isEmpty() ? "" : "; last issue=" + lastProblem);
    }
    private void checkDisk(long bytes) throws IOException {
        long free = Files.getFileStore(bufferDirectory.toAbsolutePath()).getUsableSpace();
        if (free < CONFIG.minFreeDiskMiB * 1024L * 1024)
            throw new IOException("Free disk: " + free / (1024 * 1024) + " MiB; minimum configured: " + CONFIG.minFreeDiskMiB + " MiB");
        if (bytes > CONFIG.maxBufferMiB * 1024L * 1024)
            throw new IOException("Buffer disk: " + bytes / (1024 * 1024) + " MiB; maximum configured: " + CONFIG.maxBufferMiB + " MiB");
    }
    public void startTest() {
        ClientSession client = Proxy.getInstance().getClient();
        if (!isEnabled()) throw new IllegalStateException("El modulo esta desactivado: usa golemreplay on");
        if (client == null) throw new IllegalStateException("ZenithProxy no esta conectado al servidor");
        CompletableFuture<Void> started = new CompletableFuture<>();
        client.executeInEventLoop(() -> {
            synchronized (this) {
                BufferedReplayRecording recording = null;
                try {
                    if (stopped) throw new IllegalStateException("El modulo se esta cerrando");
                    if (!isEnabled()) throw new IllegalStateException("El modulo esta desactivado: usa golemreplay on");
                    if (!client.isOnline() || client.isInQueue() || Proxy.getInstance().getClient() != client)
                        throw new IllegalStateException("ZenithProxy debe estar dentro del servidor, fuera de la cola");
                    if (testWindow != null) throw new IllegalStateException("Ya hay una prueba en curso");
                    Files.createDirectories(bufferDirectory);
                    Files.createDirectories(incidentsDirectory);
                    long bytes;
                    try (var paths = Files.walk(bufferDirectory)) {
                        bytes = paths.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
                    }
                    checkDisk(bytes);
                    if (session != client) { reset("New connection"); session = client; }
                    recording = new BufferedReplayRecording(Files.createTempDirectory(bufferDirectory, "test-"));
                    recording.startRecording();
                    if (!recording.healthy()) throw new IOException(recording.failureDescription());
                    testWindow = new RollingWindows.Window<>(recording.getStartT(), recording);
                    testWindow.deadline = recording.getStartT() + TimeUnit.SECONDS.toNanos(60);
                    if (pauseUntil > System.nanoTime()) pauseUntil = testWindow.deadline + TimeUnit.SECONDS.toNanos(1);
                    Entity player = CACHE.getPlayerCache().getThePlayer();
                    testWindow.incidents.add(new Incident(UUID.randomUUID().toString(), Instant.now().toString(), ZonedDateTime.now().toString(),
                        player.getUuid().toString(), player.getEntityId(), String.valueOf(CACHE.getChunkCache().getWorldName()),
                        player.getX(), player.getY(), player.getZ(), "MANUAL_TEST", 0, 0, 0, true, List.of(),
                        "60-second forward recording. This marker is the start of the test, not a death."));
                    LOG.info("Started 60-second snow golem recording test");
                    started.complete(null);
                } catch (Exception e) {
                    if (recording != null && testWindow != null && testWindow.value == recording) testWindow = null;
                    if (recording != null) try { recording.close(); } catch (Exception ignored) {}
                    LOG.error("Could not start replay test", e);
                    lastProblem = e.getClass().getSimpleName() + ": " + e.getMessage();
                    started.completeExceptionally(e);
                }
            }
        });
        try { started.join(); }
        catch (CompletionException e) { throw new IllegalStateException("No se pudo iniciar la prueba: " + e.getCause().getMessage(), e.getCause()); }
    }
    public synchronized void restartBuffer() {
        if (testWindow != null) throw new IllegalStateException("Espera a que termine la prueba antes de cambiar el buffer");
        CONFIG.validate(); reset("Settings changed: buffer restarted");
    }
    public void retryUploads() { delivery.retry(); }
    public synchronized String loadedGolems() {
        return tracked.isEmpty() ? "No hay golems cargados dentro del filtro de vigilancia" : tracked.values().stream().limit(30)
            .map(t -> t.uuid + " | " + (int)t.x + ", " + (int)t.y + ", " + (int)t.z + (t.dead ? " | muerto" : ""))
            .collect(java.util.stream.Collectors.joining("\n"));
    }
    public void shutdown() {
        synchronized (this) {
            if (stopped) return;
            stopped = true;
            reset("Proxy shutdown: post-event interval interrupted");
            finalizer.shutdown();
        }
        try { finalizer.awaitTermination(15, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        delivery.shutdown();
    }
    @Override public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return new PacketHandlerCodec(Integer.MIN_VALUE + 1, "snow-golem-replay", new EnumMap<>(ProtocolState.class), s -> true) {
            private void queue(Packet p, Session s, boolean incoming) {
                if (!(p instanceof MinecraftPacket mc) || !(s instanceof ClientSession client)) return;
                long time = System.nanoTime();
                try { client.executeInEventLoop(() -> packet(mc, client, time, incoming)); }
                catch (RejectedExecutionException ignored) {}
            }
            @Override public <P extends Packet, S extends Session> P handleInbound(P p, S s) { queue(p, s, true); return p; }
            @Override public <P extends Packet, S extends Session> P handleOutgoing(P p, S s) { return p; }
            @Override public <P extends Packet, S extends Session> void handlePostOutgoing(P p, S s) { queue(p, s, false); }
        };
    }
}
