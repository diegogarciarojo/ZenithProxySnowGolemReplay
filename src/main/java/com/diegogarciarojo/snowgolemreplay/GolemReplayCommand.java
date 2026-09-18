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
            .description("Records ReplayMod footage before and after snow golem deaths.")
            .usageLines("on/off", "status", "golems", "clip", "test", "retry", "discord on/off", "kiwi on/off", "channel <id|default>",
                "buffer <10..300 seconds>", "post <1..60 seconds>", "checkpoint <10..60 seconds>",
                "minFreeDisk <64..1048576 MiB>", "maxBufferDisk <64..1048576 MiB>", "watch all|list|add <uuid>|remove <uuid>")
            .build();
    }
    @Override public LiteralArgumentBuilder<CommandContext> register() {
        return command("golemreplay")
            .requires(Command::validateAccountOwner)
            .then(literal("status").executes(c -> { module.statusEmbed(c.getSource().getEmbed()); }))
            .then(literal("golems").executes(c -> { c.getSource().getEmbed().title("Loaded Snow Golems (up to 30)").description(module.loadedGolems()); }))
            .then(argument("toggle", toggle()).executes(c -> {
                CONFIG.enabled = getToggle(c, "toggle"); module.syncEnabledFromConfig(); saveConfigAsync();
                c.getSource().getEmbed().title("Snow Golem Replay " + toggleStrCaps(CONFIG.enabled));
            }))
            .then(literal("test").executes(c -> {
                module.startTest(); c.getSource().getEmbed().title("Replay Test Started")
                    .description("Records the next 60 seconds, tracks snow golem deaths, and delivers the replay using the configured upload settings.");
            }))
            .then(literal("clip").executes(c -> {
                module.saveClip(); c.getSource().getEmbed().title("Replay Clip Requested")
                    .description("Saves the available history and configured post-event interval. The recording will indicate whether the requested history is complete.");
            }))
            .then(literal("retry").executes(c -> {
                module.retryUploads(); c.getSource().getEmbed().title("Retrying Pending Uploads");
            }))
            .then(literal("discord").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.discordEnabled = getToggle(c, "toggle"); saveConfigAsync();
                c.getSource().getEmbed().title("Discord Upload " + toggleStrCaps(CONFIG.discordEnabled));
            })))
            .then(literal("kiwi").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.kiwiEnabled = getToggle(c, "toggle"); saveConfigAsync();
                c.getSource().getEmbed().title("file.kiwi Upload " + toggleStrCaps(CONFIG.kiwiEnabled));
            })))
            .then(numberSetting("buffer", 10, 300, n -> CONFIG.preSeconds = n))
            .then(numberSetting("post", 1, 60, n -> CONFIG.postSeconds = n))
            .then(numberSetting("checkpoint", 10, 60, n -> CONFIG.checkpointSeconds = n))
            .then(numberSetting("minFreeDisk", 64, 1048576, n -> CONFIG.minFreeDiskMiB = n))
            .then(numberSetting("maxBufferDisk", 64, 1048576, n -> CONFIG.maxBufferMiB = n))
            .then(literal("watch")
                .then(literal("list").executes(c -> { c.getSource().getEmbed().title("Watched Snow Golems")
                    .description(CONFIG.watchedUuids.isEmpty() ? "All loaded snow golems" : String.join("\n", CONFIG.watchedUuids)); }))
                .then(literal("all").executes(c -> {
                    module.restartBuffer(); CONFIG.watchedUuids = new java.util.ArrayList<>(); saveConfigAsync();
                    c.getSource().getEmbed().title("Watching All Snow Golems; Buffer Restarted");
                }))
                .then(literal("add").then(argument("uuid", word()).executes(c -> {
                    String uuid = java.util.UUID.fromString(getString(c, "uuid")).toString();
                    module.restartBuffer(); var ids = new java.util.ArrayList<>(CONFIG.watchedUuids);
                    if (!ids.contains(uuid)) ids.add(uuid); CONFIG.watchedUuids = ids; saveConfigAsync();
                    c.getSource().getEmbed().title("Watched UUID Added; Buffer Restarted");
                })))
                .then(literal("remove").then(argument("uuid", word()).executes(c -> {
                    String uuid = java.util.UUID.fromString(getString(c, "uuid")).toString();
                    module.restartBuffer(); var ids = new java.util.ArrayList<>(CONFIG.watchedUuids);
                    ids.remove(uuid); CONFIG.watchedUuids = ids; saveConfigAsync();
                    c.getSource().getEmbed().title("Watched UUID Removed; Buffer Restarted");
                }))))
            .then(literal("channel").then(argument("id", word()).executes(c -> {
                String id = getString(c, "id");
                if (!id.equals("default") && !id.matches("[0-9]{1,20}")) throw new IllegalArgumentException("Expected channel ID or default");
                CONFIG.discordChannelId = id.equals("default") ? "" : id; saveConfigAsync();
                c.getSource().getEmbed().title("Discord Channel Set");
            })));
    }
    @Override public void defaultEmbed(Embed embed) {
        embed.primaryColor().addField("Enabled", toggleStr(CONFIG.enabled))
            .addField("Discord / file.kiwi", "Discord: " + toggleStr(CONFIG.discordEnabled)
                + " | file.kiwi: " + toggleStr(CONFIG.kiwiEnabled))
            .addField("Replay Buffer (seconds)", "Before death: " + CONFIG.preSeconds
                + " | After death: " + CONFIG.postSeconds + " | Checkpoint: " + CONFIG.checkpointSeconds)
            .addField("Discord Channel", CONFIG.discordChannelId.isEmpty() ? "Zenith default" : CONFIG.discordChannelId)
            .addField("Watched Golems", CONFIG.watchedUuids.isEmpty() ? "All loaded snow golems" : CONFIG.watchedUuids.size() + " UUIDs")
            .addField("Disk Limits (MiB)", "Minimum free: " + CONFIG.minFreeDiskMiB + " | Maximum buffer: " + CONFIG.maxBufferMiB);
    }
    private LiteralArgumentBuilder<CommandContext> numberSetting(String name, int min, int max, java.util.function.IntConsumer setter) {
        return literal(name).then(argument("value", integer(min, max)).executes(c -> {
            synchronized (module) {
                module.restartBuffer(); setter.accept(getInteger(c, "value")); CONFIG.validate(); saveConfigAsync();
            }
            c.getSource().getEmbed().title("Settings Saved; Buffer Restarted");
        }));
    }
}
