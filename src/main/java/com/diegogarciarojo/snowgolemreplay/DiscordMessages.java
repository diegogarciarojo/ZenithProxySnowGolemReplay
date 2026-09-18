package com.diegogarciarojo.snowgolemreplay;

import com.zenith.discord.Embed;
import com.zenith.util.Color;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Native Zenith embeds, with the field arrangement used by DeathRecorder. */
final class DiscordMessages {
    private DiscordMessages() {}

    static Embed replay(String filename, List<Incident> incidents, String ending,
                        String kiwiUrl, String attachmentResult, String legacySummary) {
        if (incidents == null || incidents.isEmpty()) {
            var embed = status("Snow Golem Replay", shorten(legacySummary, 3500));
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
            .description(test ? "Prueba de grabación. Muertes detectadas: " + deaths.size() + "."
                : clip ? "Captura manual del historial disponible. Muertes detectadas: " + deaths.size() + "."
                : "A Snow Golem has died in your farm area. Replay recording captured!")
            .timestamp(Instant.parse(subject.timeUtc()));
        if (test || clip) embed.primaryColor();
        else embed.color(new Color(255, 69, 0));
        if (!deaths.isEmpty()) {
            embed.addField("Coordinates", code(String.format(Locale.ROOT, "X: %.1f, Y: %.1f, Z: %.1f", subject.x(), subject.y(), subject.z())), true)
                .addField("Cause of Death", damage(subject), true);
        }
        embed.addField("Replay File", code(filename));
        addLink(embed, kiwiUrl);
        String history = test ? "Prueba hacia adelante de 60 s" : "Historial anterior: "
            + String.format(Locale.ROOT, "%.1f", first.availablePreMs() / 1000.0) + " / " + first.requestedPreSeconds() + " s"
            + (first.completePreHistory() ? " (completo)" : " (parcial: búfer en calentamiento)");
        embed.addField("Recording", history + "\nMuertes: " + deaths.size() + " | " + ending(ending));
        if (!deaths.isEmpty()) {
            var markers = new StringBuilder();
            for (var death : deaths.stream().limit(5).toList()) {
                markers.append(String.format(Locale.ROOT, "%.2f s", death.replayTimestampMs() / 1000.0))
                    .append(" — ").append(death.uuid()).append('\n');
            }
            if (deaths.size() > 5) markers.append("Todas las marcas están dentro del replay.");
            embed.addField("Death Markers", markers.toString().strip())
                .addField("Dimension", code(subject.dimension()));
        }
        if (attachmentResult != null && attachmentResult.startsWith("Too large"))
            embed.addField("Discord Attachment", "Supera el límite de este servidor. El archivo se conserva localmente.");
        return embed;
    }

    private static String damage(Incident incident) {
        if (incident.recentDamage() == null || incident.recentDamage().isEmpty())
            return "Desconocida: el servidor no informó daño reciente.";
        var damage = incident.recentDamage().getLast();
        return "Último daño: " + code(damage.type()) + "\nCausante: " + code(shorten(damage.cause(), 160))
            + "\nDirecto: " + code(shorten(damage.direct(), 160))
            + String.format(Locale.ROOT, "\n%.2f s antes de morir. Causa final sin confirmar.", damage.ageAtDeathMs() / 1000.0);
    }

    private static String ending(String ending) {
        return switch (ending == null ? "" : ending) {
            case "Post-event interval completed" -> "Intervalo posterior completo";
            case "Manual 60-second test completed" -> "Prueba completa";
            default -> "Grabación interrumpida: " + shorten(ending, 200);
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
        if (value == null || value.isBlank()) return "Sin información";
        return value.length() <= limit ? value : value.substring(0, limit - 3) + "...";
    }
}
