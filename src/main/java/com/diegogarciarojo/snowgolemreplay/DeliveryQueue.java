package com.diegogarciarojo.snowgolemreplay;

import com.google.gson.Gson;
import com.zenith.discord.Embed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.FileUpload;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import static com.diegogarciarojo.snowgolemreplay.SnowGolemReplayPlugin.*;

/** Persistent delivery steps. Message IDs let kiwi enrich the original embed. */
final class DeliveryQueue {
    static final class Job {
        String filename, summary;
        List<Incident> incidents;
        String ending;
        boolean attachmentDone, kiwiDone, linkDone;
        String channelId = "", messageId = "";
        String kiwiUrl = "", attachmentResult = "", lastError = "";
        int attempts;
        long nextAttempt;
    }
    interface DiscordTransport {
        String defaultChannel() throws Exception;
        long attachmentLimit(String channel) throws Exception;
        String send(String channel, Embed embed, Path attachment) throws Exception;
        boolean edit(String channel, String message, Embed embed) throws Exception;
    }
    interface Uploader { String upload(Path replay, Path resume) throws Exception; }
    private final Path directory;
    private final DiscordTransport discord;
    private final Uploader uploader;
    private final boolean automatic;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "golem-replay-delivery"); t.setDaemon(true); return t;
    });
    DeliveryQueue(Path directory) { this(directory, new NativeDiscord(), (file, resume) -> new KiwiUploader().upload(file, resume), true); }
    DeliveryQueue(Path directory, DiscordTransport discord, Uploader uploader, boolean automatic) {
        this.directory = directory; this.discord = discord; this.uploader = uploader; this.automatic = automatic;
        if (automatic) worker.scheduleWithFixedDelay(this::scan, 15, 30, TimeUnit.SECONDS);
    }
    void enqueue(Path replay, List<Incident> incidents, String ending) throws IOException {
        Job job = new Job();
        job.filename = replay.getFileName().toString();
        job.incidents = List.copyOf(incidents); job.ending = ending;
        KiwiUploader.writeJson(jobPath(replay), job);
        if (automatic) worker.execute(this::scan);
    }
    private Path jobPath(Path replay) { return replay.resolveSibling(replay.getFileName() + ".delivery.json"); }
    void notifyStatus(String text) {
        if (!CONFIG.discordEnabled) return;
        worker.execute(() -> {
            try { discord.send(discord.defaultChannel(), DiscordMessages.status("Snow Golem Replay", text), null); }
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
    void deliver(Path jobPath) throws Exception {
        Job job = new Gson().fromJson(Files.readString(jobPath), Job.class);
        if (job.attempts >= 5 || System.currentTimeMillis() < job.nextAttempt || complete(job)) return;
        Path replay = directory.resolve(job.filename).normalize();
        if (!replay.getParent().equals(directory.normalize()) || !Files.isRegularFile(replay)) throw new IOException("Invalid/missing replay in delivery job");
        // 1.0.4 jobs only had text. Recover structured evidence if its sidecar exists.
        if (job.incidents == null) {
            Path reportFile = replay.resolveSibling(job.filename + ".incident.json");
            if (Files.isRegularFile(reportFile)) {
                var report = new Gson().fromJson(Files.readString(reportFile), ReplayFiles.Report.class);
                job.incidents = report.incidents(); job.ending = report.ending();
            }
        }
        job.attempts++;
        job.lastError = "";
        job.nextAttempt = System.currentTimeMillis() + Math.min(900_000L, 30_000L << job.attempts);
        KiwiUploader.writeJson(jobPath, job);
        if (CONFIG.discordEnabled && !job.attachmentDone) {
            try {
                String channel = discord.defaultChannel();
                boolean tooLarge = Files.size(replay) > discord.attachmentLimit(channel);
                job.attachmentResult = tooLarge ? "Too large for guild attachment limit" : "Uploaded";
                String message = discord.send(channel, embed(job), tooLarge ? null : replay);
                job.channelId = channel; job.messageId = message; job.attachmentDone = true;
                if (job.kiwiDone) job.linkDone = true;
                KiwiUploader.writeJson(jobPath, job);
            } catch (Exception e) { failure(jobPath, job, "Discord attachment", e); }
        }
        if (CONFIG.kiwiEnabled && !job.kiwiDone) {
            try {
                job.kiwiUrl = uploader.upload(replay, replay.resolveSibling(job.filename + ".kiwi.json"));
                job.kiwiDone = true;
                KiwiUploader.writeJson(jobPath, job);
            } catch (Exception e) { failure(jobPath, job, "file.kiwi", e); }
        }
        if (CONFIG.discordEnabled && CONFIG.kiwiEnabled && job.kiwiDone && !job.linkDone) {
            try {
                // Editing only embeds retains the original replay attachment.
                boolean edited = job.messageId != null && !job.messageId.isBlank()
                    && discord.edit(job.channelId, job.messageId, embed(job));
                if (!edited) {
                    String channel = discord.defaultChannel();
                    job.messageId = discord.send(channel, embed(job), null); job.channelId = channel;
                }
                job.linkDone = true;
                KiwiUploader.writeJson(jobPath, job);
            } catch (Exception e) { failure(jobPath, job, "Discord link", e); }
        }
        if (complete(job)) { job.lastError = ""; KiwiUploader.writeJson(jobPath, job); }
    }
    private static boolean complete(Job job) {
        return (job.attachmentDone || !CONFIG.discordEnabled) && (job.kiwiDone || !CONFIG.kiwiEnabled)
            && (job.linkDone || !CONFIG.discordEnabled || !CONFIG.kiwiEnabled);
    }
    private static Embed embed(Job job) {
        return DiscordMessages.replay(job.filename, job.incidents, job.ending,
            job.kiwiDone ? job.kiwiUrl : "", job.attachmentResult, job.summary);
    }
    private void failure(Path path, Job job, String step, Exception e) throws IOException {
        // HTTP exceptions can contain signed URLs/secrets. Keep only the exception type.
        job.lastError = step + ": " + e.getClass().getSimpleName();
        KiwiUploader.writeJson(path, job);
        LOG.warn("{} failed for {} (attempt {}/5). Local replay retained.", step, job.filename, job.attempts);
    }
    private static final class NativeDiscord implements DiscordTransport {
        @Override public String defaultChannel() {
            return CONFIG.discordChannelId.isEmpty() ? com.zenith.Globals.CONFIG.discord.channelId : CONFIG.discordChannelId;
        }
        private TextChannel channel(String id) throws IOException {
            var bot = com.zenith.Globals.DISCORD;
            if (!bot.isRunning() || bot.jda() == null) throw new IOException("Discord not connected");
            TextChannel channel = bot.jda().getTextChannelById(id);
            if (channel == null) throw new IOException("Discord channel unavailable");
            return channel;
        }
        @Override public long attachmentLimit(String id) throws Exception { return channel(id).getGuild().getMaxFileSize(); }
        @Override public String send(String id, Embed embed, Path attachment) throws Exception {
            var action = channel(id).sendMessageEmbeds(embed.toJDAEmbed()).setAllowedMentions(List.of());
            if (attachment == null) return action.submit().get(90, TimeUnit.SECONDS).getId();
            try (var upload = FileUpload.fromData(attachment.toFile())) {
                return action.addFiles(upload).submit().get(120, TimeUnit.SECONDS).getId();
            }
        }
        @Override public boolean edit(String id, String message, Embed embed) throws Exception {
            try {
                channel(id).editMessageEmbedsById(message, embed.toJDAEmbed()).submit().get(90, TimeUnit.SECONDS);
                return true;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof ErrorResponseException error && error.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE)
                    return false;
                throw e;
            }
        }
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
