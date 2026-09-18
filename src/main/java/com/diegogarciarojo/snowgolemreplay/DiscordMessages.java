package com.diegogarciarojo.snowgolemreplay;

import com.zenith.discord.Embed;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Native Zenith embeds, with the field arrangement used by DeathRecorder. */
final class DiscordMessages {
    private DiscordMessages() {}

    static Embed replay(String filename, List<Incident> incidents, String ending,
                        String kiwiUrl, String attachmentResult, String legacySummary) {
        if (incidents == null || incidents.isEmpty()) {
            var embed = status("Snow Golem Replay", "A saved replay is ready for delivery. Incident details are unavailable.");
            embed.addField("Replay File", code(filename));
            addLink(embed, kiwiUrl);
            return embed;
        }
        var first = incidents.getFirst();
        var deaths = incidents.stream().filter(Incident::isDeath).toList();
        var subject = deaths.isEmpty() ? first : deaths.getFirst();
        boolean test = first.confirmation().equals("MANUAL_TEST");
        boolean clip = first.confirmation().equals("MANUAL_CLIP");
        var embed = Embed.builder()
            .title(test ? "Snow Golem Replay Test" : clip ? "Snow Golem Replay Clip" : "Snow Golem Death Detected!")
            .description(test ? "Replay test recording. Deaths detected: " + deaths.size() + "."
                : clip ? "Manual replay clip. Deaths detected: " + deaths.size() + "."
                : "A Snow Golem has died in your farm area. Replay recording captured!")
            .timestamp(Instant.parse(subject.timeUtc()));
        if (test || clip) embed.primaryColor();
        else embed.errorColor();
        if (!deaths.isEmpty()) {
            embed.addField("Coordinates", code(String.format(Locale.ROOT, "X: %.1f, Y: %.1f, Z: %.1f", subject.x(), subject.y(), subject.z())), true)
                .addField("Cause of Death", damage(subject), true);
        }
        embed.addField("Replay File", code(filename));
        addLink(embed, kiwiUrl);
        String history = test ? "60-second forward recording" : "Pre-event history: "
            + String.format(Locale.ROOT, "%.1f", first.availablePreMs() / 1000.0) + " / " + first.requestedPreSeconds() + " s"
            + (first.completePreHistory() ? " (complete)" : " (partial: buffer warming up)");
        embed.addField("Recording", history + "\nDeaths: " + deaths.size() + " | " + ending(ending));
        if (!deaths.isEmpty()) {
            var markers = new StringBuilder();
            for (var death : deaths.stream().limit(5).toList()) {
                markers.append(String.format(Locale.ROOT, "%.2f s", death.replayTimestampMs() / 1000.0))
                    .append(" — ").append(death.uuid()).append('\n');
            }
            if (deaths.size() > 5) markers.append("All markers are included in the replay.");
            embed.addField("Death Markers", markers.toString().strip())
                .addField("Dimension", code(subject.dimension()));
        }
        if (attachmentResult != null && attachmentResult.startsWith("Too large"))
            embed.addField("Discord Attachment", "Exceeds this server's attachment limit. The file is retained locally.");
        return embed;
    }

    private static String damage(Incident incident) {
        if (incident.recentDamage() == null || incident.recentDamage().isEmpty())
            return "Unknown: the server did not report recent damage.";
        var damage = incident.recentDamage().getLast();
        return "Last damage: " + code(damage.type()) + "\nSource: " + code(shorten(damage.cause(), 160))
            + "\nDirect entity: " + code(shorten(damage.direct(), 160))
            + String.format(Locale.ROOT, "\n%.2f s before death. Final cause unconfirmed.", damage.ageAtDeathMs() / 1000.0);
    }

    private static String ending(String ending) {
        return switch (ending == null ? "" : ending) {
            case "Post-event interval completed" -> "Post-event interval complete";
            case "Manual 60-second test completed" -> "Test complete";
            default -> "Recording interrupted: " + shorten(ending, 200);
        };
    }

    private static void addLink(Embed embed, String link) {
        if (link != null && !link.isBlank())
            embed.addField("File.kiwi Download Link", "[Click to Download Replay](" + link + ")");
    }

    static Embed status(String title, String description) {
        return Embed.builder().title(title).description(description).primaryColor().timestamp(Instant.now());
    }

    private static String code(String value) { return "`" + (value == null ? "unknown" : value.replace('`', '\'')) + "`"; }
    private static String shorten(String value, int limit) {
        if (value == null || value.isBlank()) return "Not available";
        return value.length() <= limit ? value : value.substring(0, limit - 3) + "...";
    }
}
