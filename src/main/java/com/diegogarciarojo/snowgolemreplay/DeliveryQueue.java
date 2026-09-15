package com.diegogarciarojo.snowgolemreplay;

import com.google.gson.Gson;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import static com.diegogarciarojo.snowgolemreplay.SnowGolemReplayPlugin.*;

/** Persistent independent delivery steps: Discord failure never prevents a kiwi upload. */
final class DeliveryQueue {
    static final class Job {
        String filename, summary;
        boolean attachmentDone, kiwiDone, linkDone;
        String kiwiUrl = "", attachmentResult = "", lastError = "";
        int attempts;
        long nextAttempt;
    }
    private final Path directory;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "golem-replay-delivery"); t.setDaemon(true); return t;
    });
    DeliveryQueue(Path directory) {
        this.directory = directory;
        worker.scheduleWithFixedDelay(this::scan, 15, 30, TimeUnit.SECONDS);
    }
    void enqueue(Path replay, List<Incident> incidents, String ending) throws IOException {
        var first = incidents.getFirst();
        Job job = new Job();
        job.filename = replay.getFileName().toString();
        job.summary = ("MANUAL_TEST".equals(first.confirmation()) ? "Prueba manual de replay" : "Muerte de golem de nieve")
            + " | " + first.timeUtc() + "\nGolem: " + first.uuid() + "\nMarca: "
            + String.format(java.util.Locale.ROOT, "%.2f s", first.replayTimestampMs() / 1000.0)
            + "; historial anterior: " + first.availablePreMs() / 1000 + " s"
            + (first.completePreHistory() ? "" : " (buffer en calentamiento; historial parcial)")
            + ". Incidentes en este replay: " + incidents.size()
            + "\nMuertes de golems detectadas: " + incidents.stream().filter(i -> !i.confirmation().equals("MANUAL_TEST")).count()
            + "\nFinalizacion: " + ending
            + "\nLa causa se investiga con el replay y golem-incident.json; el ultimo dano no prueba por si solo la causa final.";
        KiwiUploader.writeJson(jobPath(replay), job);
        worker.execute(this::scan);
    }
    private Path jobPath(Path replay) { return replay.resolveSibling(replay.getFileName() + ".delivery.json"); }
    void notifyStatus(String text) {
        if (!CONFIG.discordEnabled) return;
        worker.execute(() -> {
            try { send(channel(), text); }
            catch (Exception e) { LOG.warn("Replay status notification failed ({})", e.getClass().getSimpleName()); }
        });
    }
    private void scan() {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".mcpr.delivery.json")).sorted().toList()) {
                try { deliver(p); }
                catch (Exception e) { LOG.warn("Delivery job could not be processed: {} ({})", p.getFileName(), e.getClass().getSimpleName()); }
            }
        } catch (IOException e) { LOG.warn("Cannot scan replay delivery queue"); }
    }
    private void deliver(Path jobPath) throws Exception {
        Job job = new Gson().fromJson(Files.readString(jobPath), Job.class);
        if (job.attempts >= 5 || System.currentTimeMillis() < job.nextAttempt) return;
        if ((job.attachmentDone || !CONFIG.discordEnabled) && (job.kiwiDone || !CONFIG.kiwiEnabled)
            && (job.linkDone || !CONFIG.discordEnabled || !CONFIG.kiwiEnabled)) return;
        Path replay = directory.resolve(job.filename).normalize();
        if (!replay.getParent().equals(directory.normalize()) || !Files.isRegularFile(replay)) throw new IOException("Invalid/missing replay in delivery job");
        job.attempts++;
        job.nextAttempt = System.currentTimeMillis() + Math.min(900_000L, 30_000L << job.attempts);
        KiwiUploader.writeJson(jobPath, job);
        if (CONFIG.discordEnabled && !job.attachmentDone) {
            try {
                TextChannel channel = channel();
                if (Files.size(replay) > channel.getGuild().getMaxFileSize()) {
                    send(channel, job.summary + "\nEl replay supera el limite de adjuntos de este servidor de Discord; se conserva localmente y se intentara file.kiwi.");
                    job.attachmentResult = "Too large for guild attachment limit";
                } else {
                    try (var upload = FileUpload.fromData(replay.toFile())) {
                        channel.sendMessage(job.summary).setAllowedMentions(List.of()).addFiles(upload).submit().get(120, TimeUnit.SECONDS);
                    }
                    job.attachmentResult = "Uploaded";
                }
                job.attachmentDone = true;
                KiwiUploader.writeJson(jobPath, job);
            } catch (Exception e) { failure(jobPath, job, "Discord attachment", e); }
        }
        if (CONFIG.kiwiEnabled && !job.kiwiDone) {
            try {
                job.kiwiUrl = new KiwiUploader().upload(replay, replay.resolveSibling(job.filename + ".kiwi.json"));
                job.kiwiDone = true;
                KiwiUploader.writeJson(jobPath, job);
            } catch (Exception e) { failure(jobPath, job, "file.kiwi", e); }
        }
        if (CONFIG.discordEnabled && CONFIG.kiwiEnabled && job.kiwiDone && !job.linkDone) {
            try {
                send(channel(), job.summary + "\nReplay verificado en file.kiwi:\n" + job.kiwiUrl
                    + "\nDescarga gratuita temporal; conserva tu copia local.");
                job.linkDone = true;
                KiwiUploader.writeJson(jobPath, job);
            } catch (Exception e) { failure(jobPath, job, "Discord link", e); }
        }
    }
    private void failure(Path path, Job job, String step, Exception e) throws IOException {
        // Do not log exception messages: HTTP exceptions can contain signed URLs/secrets.
        job.lastError = step + ": " + e.getClass().getSimpleName();
        KiwiUploader.writeJson(path, job);
        LOG.warn("{} failed for {} (attempt {}/5). Local replay retained.", step, job.filename, job.attempts);
    }
    private TextChannel channel() throws IOException {
        var discord = com.zenith.Globals.DISCORD;
        if (!discord.isRunning() || discord.jda() == null) throw new IOException("Discord not connected");
        String id = CONFIG.discordChannelId.isEmpty() ? com.zenith.Globals.CONFIG.discord.channelId : CONFIG.discordChannelId;
        TextChannel channel = discord.jda().getTextChannelById(id);
        if (channel == null) throw new IOException("Discord channel unavailable");
        return channel;
    }
    private void send(TextChannel channel, String content) throws Exception {
        channel.sendMessage(content).setAllowedMentions(List.of()).submit().get(90, TimeUnit.SECONDS);
    }
    void retry() {
        worker.execute(() -> {
            try (var paths = Files.list(directory)) {
                for (Path p : paths.filter(f -> f.getFileName().toString().endsWith(".mcpr.delivery.json")).toList()) {
                    var job = new Gson().fromJson(Files.readString(p), Job.class);
                    job.attempts = 0; job.nextAttempt = 0;
                    KiwiUploader.writeJson(p, job);
                }
            } catch (Exception e) { LOG.warn("Could not reset delivery retries"); }
            scan();
        });
    }
    void shutdown() { worker.shutdownNow(); }
}
