package com.diegogarciarojo.snowgolemreplay;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

@Plugin(id = BuildConstants.PLUGIN_ID, version = BuildConstants.VERSION,
    description = "Snow golem death evidence with pre-event ReplayMod recording, Discord and file.kiwi",
    authors = {"diegogarciarojo"}, mcVersions = {BuildConstants.MC_VERSION})
public class SnowGolemReplayPlugin implements ZenithProxyPlugin {
    public static GolemReplayConfig CONFIG;
    public static ComponentLogger LOG;

    @Override public void onLoad(PluginAPI api) {
        LOG = api.getLogger();
        CONFIG = api.registerConfig(BuildConstants.PLUGIN_ID, GolemReplayConfig.class);
        CONFIG.validate();
        GolemReplayModule module = new GolemReplayModule();
        api.registerModule(module);
        api.registerCommand(new GolemReplayCommand(module));
        Runtime.getRuntime().addShutdownHook(new Thread(module::shutdown, "golem-replay-shutdown"));
        LOG.info("SnowGolemReplay loaded: {}s before, {}s after death", CONFIG.preSeconds, CONFIG.postSeconds);
    }
}
