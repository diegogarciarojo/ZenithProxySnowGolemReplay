/*
 * Adapted from ZenithProxy 3.7.0+1.21.4 ReplayRecording, copyright its contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 * Changes: safe close/drain, bounded writer queue, explicit serialization failures.
 * See THIRD_PARTY_NOTICES.md and LICENSE.
 */
package com.diegogarciarojo.snowgolemreplay;

import com.zenith.feature.replay.ReplayMetadata;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.zenith.Proxy;
import com.zenith.feature.player.World;
import com.zenith.feature.spectator.SpectatorPacketProvider;
import com.zenith.module.impl.ReplayMod;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundUpdateEnabledFeaturesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.*;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;

import java.io.*;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.zenith.Globals.*;

public class BufferedReplayRecording implements Closeable {
    private final ReplayMetadata metadata;
    private final Path replayDirectory;
    private OutputStream fileOutputStream;
    private ZipOutputStream zipOutputStream;
    private OutputStream writerStream;
    private File replayFile;
    private static final ByteBufAllocator ALLOC = ByteBufAllocator.DEFAULT;
    private boolean preConnectSyncNeeded = false;
    private volatile long startT;
    private final ExecutorService executor = new java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0, TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(8192),
        new ThreadFactoryBuilder()
            .setNameFormat("ZenithProxy ReplayMod PacketHandler #%d")
            .setDaemon(true)
            .setUncaughtExceptionHandler((t, e) -> MODULE.get(ReplayMod.class).error("Uncaught exception in thread {}", t.getName(), e))
            .build(), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private volatile boolean started = false;
    private volatile boolean closed = false;
    private volatile Throwable writeFailure;
    public File getReplayFile() { return replayFile; }
    public long getStartT() { return startT; }
    public boolean healthy() { return writeFailure == null; }

    public BufferedReplayRecording(final Path replayDirectory) {
        this.metadata = new ReplayMetadata();
        this.replayDirectory = replayDirectory;
    }

    public boolean ready() {
        return started && !closed;
    }

    public void startRecording() throws Exception {
        if (started) throw new IllegalStateException("Already started");
        // initialize output streams and metadata
        var serverName = CONFIG.client.server.address;
        if (CONFIG.client.server.port != 25565)
            serverName += ":" + CONFIG.client.server.port;
        metadata.setServerName(serverName);
        metadata.setDate(System.currentTimeMillis());
        metadata.setMcversion(MinecraftCodec.CODEC.getMinecraftVersion());
        metadata.setProtocol(MinecraftCodec.CODEC.getProtocolVersion());
        // todo: when to init?
//        metadata.setSelfId(CACHE.getPlayerCache().getEntityId());
        final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
        final String time = formatter.format(ZonedDateTime.now());
        replayFile = replayDirectory.resolve(time + "_" + CONFIG.authentication.username + ".mcpr").toFile();
        replayFile.getParentFile().mkdirs();
        fileOutputStream = new FileOutputStream(replayFile);
        zipOutputStream = new ZipOutputStream(fileOutputStream);
        zipOutputStream.putNextEntry(new ZipEntry("recording.tmcpr"));
        writerStream = new BufferedOutputStream(zipOutputStream);
        if (Proxy.getInstance().isConnected() && Proxy.getInstance().getClient().isOnline()) {
            lateStartRecording();
        } else {
            preConnectRecording();
        }
        started = true;
    }

    // Start recording while we already have a logged in session
    private synchronized void lateStartRecording() {
        writePacket0(0, new ClientboundLoginFinishedPacket(CACHE.getProfileCache().getProfile()), Proxy.getInstance().getClient(), ProtocolState.LOGIN);
        CACHE.getRegistriesCache().getRegistryPackets(
            packet -> writePacket(System.nanoTime(), (MinecraftPacket) packet, Proxy.getInstance().getClient(), ProtocolState.CONFIGURATION),
            Proxy.getInstance().getClient());
        CACHE.getConfigurationCache().getConfigurationPackets(
            packet -> writePacket(System.nanoTime(), (MinecraftPacket) packet, Proxy.getInstance().getClient(), ProtocolState.CONFIGURATION),
            Proxy.getInstance().getClient());
        writePacket(System.nanoTime(), new ClientboundCustomPayloadPacket(Key.key("minecraft:brand"), CACHE.getChunkCache().getServerBrand()), Proxy.getInstance().getClient(), ProtocolState.CONFIGURATION);
        writePacket(System.nanoTime(), new ClientboundFinishConfigurationPacket(), Proxy.getInstance().getClient(), ProtocolState.CONFIGURATION);
        writePacket(System.nanoTime(), new ClientboundLoginPacket(
            CACHE.getPlayerCache().getEntityId(),
            CACHE.getPlayerCache().isHardcore(),
            CACHE.getChunkCache().getWorldNames().toArray(new Key[0]),
            CACHE.getPlayerCache().getMaxPlayers(),
            CACHE.getChunkCache().getServerViewDistance(),
            CACHE.getChunkCache().getServerSimulationDistance(),
            CACHE.getPlayerCache().isReducedDebugInfo(),
            CACHE.getPlayerCache().isEnableRespawnScreen(),
            CACHE.getPlayerCache().isDoLimitedCrafting(),
            new PlayerSpawnInfo(
                World.getCurrentDimension().id(),
                CACHE.getChunkCache().getWorldName(),
                CACHE.getChunkCache().getHashedSeed(),
                CACHE.getPlayerCache().getGameMode(),
                CACHE.getPlayerCache().getGameMode(),
                CACHE.getChunkCache().isDebug(),
                CACHE.getChunkCache().isFlat(),
                CACHE.getPlayerCache().getLastDeathPos(),
                CACHE.getPlayerCache().getPortalCooldown(),
                CACHE.getChunkCache().getSeaLevel()
            ),
            false
        ), Proxy.getInstance().getClient());
        CACHE.getAllData().forEach(d ->
            d.getPackets(packet -> writePacket(System.nanoTime(), (MinecraftPacket) packet, Proxy.getInstance().getClient()), Proxy.getInstance().getClient()));
        SpectatorPacketProvider.playerSpawn().forEach(p -> writePacket(System.nanoTime(), (MinecraftPacket) p, Proxy.getInstance().getClient()));
        SpectatorPacketProvider.playerPose().forEach(p -> writePacket(System.nanoTime(), (MinecraftPacket) p, Proxy.getInstance().getClient()));
        SpectatorPacketProvider.playerPosition().forEach(p -> writePacket(System.nanoTime(), (MinecraftPacket) p, Proxy.getInstance().getClient()));
        SpectatorPacketProvider.playerEquipment().forEach(p -> writePacket(System.nanoTime(), (MinecraftPacket) p, Proxy.getInstance().getClient()));
    }

    // Start recording before we've connected
    // need to wait for login packets
    private void preConnectRecording() {
        preConnectSyncNeeded = true;
    }

    public void writePacket(final long time, final MinecraftPacket packet, final Session session) {
        var protocolState = session.getPacketProtocol().getOutboundState();
        if (protocolState != ProtocolState.GAME) return;
        enqueue(time, packet, session, protocolState);
    }

    public void writePacket(final long time, final MinecraftPacket packet, final Session session, ProtocolState protocolState) {
        enqueue(time, packet, session, protocolState);
    }

    private void enqueue(long time, MinecraftPacket packet, Session session, ProtocolState state) {
        if (closed) return;
        try { executor.execute(() -> writePacket0(time, packet, session, state)); }
        catch (java.util.concurrent.RejectedExecutionException e) { writeFailure = e; }
    }

    private synchronized void writePacket0(final long time, final MinecraftPacket packet, final Session session, final ProtocolState protocolState) {
        try {
            writeToFile(time, packet, session, protocolState);
        } catch (final Throwable e) {
            writeFailure = e;
            MODULE.get(ReplayMod.class).error("Failed to write packet {}", packet.getClass().getSimpleName(), e);
        }
    }

    private synchronized void writeToFile(final long time, MinecraftPacket packet, final Session session, final ProtocolState protocolState) {
        if (!CONFIG.client.extra.replayMod.featureFlags) {
            if (packet instanceof ClientboundUpdateEnabledFeaturesPacket) {
                packet = new ClientboundUpdateEnabledFeaturesPacket(new String[]{"minecraft:vanilla"});
            }
        }
        int t = time == 0
            ? 0
            : Math.max(1, (int) TimeUnit.NANOSECONDS.toMillis(time - startT));
        if (t == 0) startT = System.nanoTime();
        final ByteBuf packetBuf = ALLOC.heapBuffer();
        try {
            packetBuf.writeInt(t);
            var lenIndex = packetBuf.writerIndex();
            packetBuf.writeInt(0); // write dummy length
            var packetProtocol = session.getPacketProtocol();
            var packetId = MinecraftCodec.CODEC.getCodec(protocolState).getClientboundId(packet);
            packetProtocol.getPacketHeader().writePacketId(packetBuf, packetId);
            packet.serialize(packetBuf);
            var packetSize = packetBuf.readableBytes();
            var packetBodySize = packetSize - 8;
            packetBuf.setInt(lenIndex, packetBodySize); // write actual length
            packetBuf.readBytes(writerStream, packetSize);
        } catch (final Throwable e) {
            writeFailure = e;
            MODULE.get(ReplayMod.class).error("Failed to write packet {}", packet.getClass().getSimpleName(), e);
        } finally {
            packetBuf.release();
        }
    }

    @Override
    public void close() throws IOException {
        // Ownership guarantees no producers remain. Never hold this monitor while draining:
        // writePacket0/writeToFile acquire it from the writer thread.
        if (closed) return;
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) throw new IOException("Replay writer did not stop; keeping source file");
                writeFailure = new IOException("Replay writer timed out");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow(); Thread.currentThread().interrupt();
            throw new IOException("Interrupted while draining replay", e);
        }
        if (writerStream != null) {
            writerStream.flush();
            zipOutputStream.closeEntry();
            metadata.setDuration((int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startT));
            zipOutputStream.putNextEntry(new ZipEntry("metaData.json"));
            zipOutputStream.write(GSON.toJson(metadata).getBytes());
            zipOutputStream.closeEntry();
            writerStream.close();
            zipOutputStream.close();
        }
        if (fileOutputStream != null) {
            fileOutputStream.close();
        }
        if (writeFailure != null) throw new IOException("Replay serialization failed; source retained for diagnosis", writeFailure);
    }

    private boolean recordSelfSpawn = false;

    public void handleOutgoingPacket(final long time, final MinecraftPacket packet, final Session session) {
        if (preConnectSyncNeeded) return;
        if (packet instanceof ServerboundAcceptTeleportationPacket) {
            if (recordSelfSpawn) {
                recordSelfSpawn = false;
                SpectatorPacketProvider.playerSpawn().forEach(p -> writePacket(time, (MinecraftPacket) p, session, ProtocolState.GAME));
                SpectatorPacketProvider.playerPose().forEach(p -> writePacket(time, (MinecraftPacket) p, session, ProtocolState.GAME));
            }
        } else if (packet instanceof ServerboundMovePlayerPosPacket
            || packet instanceof ServerboundMovePlayerPosRotPacket
            || packet instanceof ServerboundMovePlayerRotPacket) {
            SpectatorPacketProvider.playerPosition().forEach(p -> writePacket(time, (MinecraftPacket) p, session, ProtocolState.GAME));
        } else if (packet instanceof ServerboundContainerClickPacket
            || packet instanceof ServerboundContainerClosePacket
            || packet instanceof ServerboundPlayerActionPacket) {
            SpectatorPacketProvider.playerEquipment().forEach(p -> writePacket(time, (MinecraftPacket) p, session, ProtocolState.GAME));
        } else if (packet instanceof ServerboundSwingPacket) {
            SpectatorPacketProvider.playerSwing().forEach(p -> writePacket(time, (MinecraftPacket) p, session, ProtocolState.GAME));
        } else if (packet instanceof ServerboundPlayerCommandPacket) {
            SpectatorPacketProvider.playerPose().forEach(p -> writePacket(time, (MinecraftPacket) p, session, ProtocolState.GAME));
        }
        /**
         * Known issues because we don't cache these states:
         *
         * Block breaking progress
         * Sleeping animation
         */
    }

    public void handleInboundPacket(long time, final MinecraftPacket packet, final Session session) {
        if (packet instanceof ClientboundLoginPacket) {
            recordSelfSpawn = true;
            if (preConnectSyncNeeded) {
                writePacket0(0, new ClientboundLoginFinishedPacket(CACHE.getProfileCache().getProfile()), session, ProtocolState.LOGIN);
                CACHE.getRegistriesCache().getRegistryPackets(
                    packet2 -> writePacket(System.nanoTime(), (MinecraftPacket) packet2, Proxy.getInstance().getClient(), ProtocolState.CONFIGURATION),
                    Proxy.getInstance().getClient());
                CACHE.getConfigurationCache().getConfigurationPackets(
                    packet2 -> writePacket(System.nanoTime(), (MinecraftPacket) packet2, Proxy.getInstance().getClient(), ProtocolState.CONFIGURATION),
                    Proxy.getInstance().getClient());
                writePacket(System.nanoTime(), new ClientboundCustomPayloadPacket(Key.key("minecraft:brand"), CACHE.getChunkCache().getServerBrand()), Proxy.getInstance().getClient(), ProtocolState.CONFIGURATION);
                writePacket(System.nanoTime(), new ClientboundFinishConfigurationPacket(), Proxy.getInstance().getClient(), ProtocolState.CONFIGURATION);
                time = System.nanoTime();
                preConnectSyncNeeded = false;
            }
        }
        if (preConnectSyncNeeded) {
            return;
        }
        if (session.getPacketProtocol().getOutboundState() == ProtocolState.GAME
            && session.getPacketProtocol().getInboundState() == ProtocolState.GAME) {
            writePacket(time, packet, session, ProtocolState.GAME);
        }
        if (packet instanceof ClientboundRespawnPacket) {
            final long t = time;
            SpectatorPacketProvider.playerSpawn().forEach(p -> writePacket(t, (MinecraftPacket) p, session));
            SpectatorPacketProvider.playerPose().forEach(p -> writePacket(t, (MinecraftPacket) p, session));
        }
    }
}

