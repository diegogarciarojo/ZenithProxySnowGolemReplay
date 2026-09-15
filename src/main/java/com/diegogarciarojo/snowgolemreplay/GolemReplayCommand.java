package com.diegogarciarojo.snowgolemreplay;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.*;
import com.zenith.discord.Embed;
import static com.zenith.Globals.saveConfigAsync;
import static com.zenith.command.brigadier.ToggleArgumentType.*;
import static com.mojang.brigadier.arguments.StringArgumentType.*;
import static com.mojang.brigadier.arguments.IntegerArgumentType.*;
import static com.diegogarciarojo.snowgolemreplay.SnowGolemReplayPlugin.CONFIG;

public final class GolemReplayCommand extends Command {
    private final GolemReplayModule module;
    GolemReplayCommand(GolemReplayModule module) { this.module = module; }
    @Override public CommandUsage commandUsage() {
        return CommandUsage.builder().name("golemreplay").category(CommandCategory.MODULE)
            .description("Replay anterior a la muerte de golems de nieve")
            .usageLines("on/off", "status", "golems", "test", "retry", "discord on/off", "kiwi on/off", "channel <id|default>",
                "buffer <10..300 seconds>", "post <1..60 seconds>", "checkpoint <10..60 seconds>",
                "minFreeDisk <64..1048576 MiB>", "maxBufferDisk <64..1048576 MiB>", "watch all|list|add <uuid>|remove <uuid>")
            .build();
    }
    @Override public LiteralArgumentBuilder<CommandContext> register() {
        return command("golemreplay")
            .requires(Command::validateAccountOwner)
            .then(literal("status").executes(c -> { c.getSource().getEmbed().title("Snow Golem Replay").description(module.status()); }))
            .then(literal("golems").executes(c -> { c.getSource().getEmbed().title("Golems cargados (hasta 30)").description(module.loadedGolems()); }))
            .then(argument("toggle", toggle()).executes(c -> {
                CONFIG.enabled = getToggle(c, "toggle"); module.syncEnabledFromConfig(); saveConfigAsync();
                c.getSource().getEmbed().title("Snow Golem Replay " + toggleStrCaps(CONFIG.enabled));
            }))
            .then(literal("test").executes(c -> {
                module.startTest(); c.getSource().getEmbed().title("Prueba de 60 segundos solicitada")
                    .description("Graba desde ahora durante un minuto, cuenta las muertes y despues ejecuta las subidas configuradas.");
            }))
            .then(literal("retry").executes(c -> {
                module.retryUploads(); c.getSource().getEmbed().title("Reintentando entregas pendientes");
            }))
            .then(literal("discord").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.discordEnabled = getToggle(c, "toggle"); saveConfigAsync();
                c.getSource().getEmbed().title("Discord: " + CONFIG.discordEnabled);
            })))
            .then(literal("kiwi").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.kiwiEnabled = getToggle(c, "toggle"); saveConfigAsync();
                c.getSource().getEmbed().title("file.kiwi: " + CONFIG.kiwiEnabled);
            })))
            .then(numberSetting("buffer", 10, 300, n -> CONFIG.preSeconds = n))
            .then(numberSetting("post", 1, 60, n -> CONFIG.postSeconds = n))
            .then(numberSetting("checkpoint", 10, 60, n -> CONFIG.checkpointSeconds = n))
            .then(numberSetting("minFreeDisk", 64, 1048576, n -> CONFIG.minFreeDiskMiB = n))
            .then(numberSetting("maxBufferDisk", 64, 1048576, n -> CONFIG.maxBufferMiB = n))
            .then(literal("watch")
                .then(literal("list").executes(c -> { c.getSource().getEmbed().title("Golems vigilados")
                    .description(CONFIG.watchedUuids.isEmpty() ? "Todos los golems cargados" : String.join("\n", CONFIG.watchedUuids)); }))
                .then(literal("all").executes(c -> {
                    module.restartBuffer(); CONFIG.watchedUuids = new java.util.ArrayList<>(); saveConfigAsync();
                    c.getSource().getEmbed().title("Vigilando todos los golems cargados; buffer reiniciado");
                }))
                .then(literal("add").then(argument("uuid", word()).executes(c -> {
                    String uuid = java.util.UUID.fromString(getString(c, "uuid")).toString();
                    module.restartBuffer(); var ids = new java.util.ArrayList<>(CONFIG.watchedUuids);
                    if (!ids.contains(uuid)) ids.add(uuid); CONFIG.watchedUuids = ids; saveConfigAsync();
                    c.getSource().getEmbed().title("UUID anadido; buffer reiniciado");
                })))
                .then(literal("remove").then(argument("uuid", word()).executes(c -> {
                    String uuid = java.util.UUID.fromString(getString(c, "uuid")).toString();
                    module.restartBuffer(); var ids = new java.util.ArrayList<>(CONFIG.watchedUuids);
                    ids.remove(uuid); CONFIG.watchedUuids = ids; saveConfigAsync();
                    c.getSource().getEmbed().title("UUID eliminado; buffer reiniciado");
                }))))
            .then(literal("channel").then(argument("id", word()).executes(c -> {
                String id = getString(c, "id");
                if (!id.equals("default") && !id.matches("[0-9]{1,20}")) throw new IllegalArgumentException("Expected channel ID or default");
                CONFIG.discordChannelId = id.equals("default") ? "" : id; saveConfigAsync();
                c.getSource().getEmbed().title("Canal de Discord actualizado");
            })));
    }
    @Override public void defaultEmbed(Embed embed) {
        embed.primaryColor().addField("Enabled", toggleStr(CONFIG.enabled))
            .addField("Discord / file.kiwi", "Discord: " + toggleStr(CONFIG.discordEnabled)
                + " | file.kiwi: " + toggleStr(CONFIG.kiwiEnabled))
            .addField("Replay buffer (seconds)", "Before death: " + CONFIG.preSeconds
                + " | After death: " + CONFIG.postSeconds + " | Checkpoint: " + CONFIG.checkpointSeconds)
            .addField("Discord channel", CONFIG.discordChannelId.isEmpty() ? "Zenith default" : CONFIG.discordChannelId)
            .addField("Watched golems", CONFIG.watchedUuids.isEmpty() ? "All loaded snow golems" : CONFIG.watchedUuids.size() + " UUIDs")
            .addField("Disk limits (MiB)", "Minimum free: " + CONFIG.minFreeDiskMiB + " | Maximum buffer: " + CONFIG.maxBufferMiB);
    }
    private LiteralArgumentBuilder<CommandContext> numberSetting(String name, int min, int max, java.util.function.IntConsumer setter) {
        return literal(name).then(argument("value", integer(min, max)).executes(c -> {
            synchronized (module) {
                module.restartBuffer(); setter.accept(getInteger(c, "value")); CONFIG.validate(); saveConfigAsync();
            }
            c.getSource().getEmbed().title("Configuracion guardada; buffer reiniciado");
        }));
    }
}
