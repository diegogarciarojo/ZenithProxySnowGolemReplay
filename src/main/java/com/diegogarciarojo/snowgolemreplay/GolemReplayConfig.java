package com.diegogarciarojo.snowgolemreplay;

import java.util.ArrayList;
import java.util.List;

public class GolemReplayConfig {
    public volatile boolean enabled = true;
    public volatile int preSeconds = 60;
    public volatile int postSeconds = 10;
    public volatile int checkpointSeconds = 15;
    public volatile long minFreeDiskMiB = 256;
    public volatile long maxBufferMiB = 2048;
    public volatile boolean discordEnabled = true;
    public volatile boolean kiwiEnabled = true;
    /** Empty uses ZenithProxy's existing Discord channel. */
    public volatile String discordChannelId = "";
    /** Empty watches all loaded snow golems. UUIDs survive entity-id reuse. */
    public volatile List<String> watchedUuids = new ArrayList<>();

    public void validate() {
        if (preSeconds < 10 || preSeconds > 300) throw new IllegalArgumentException("preSeconds: 10..300");
        if (postSeconds < 1 || postSeconds > 60) throw new IllegalArgumentException("postSeconds: 1..60");
        if (checkpointSeconds < 10 || checkpointSeconds > 60) throw new IllegalArgumentException("checkpointSeconds: 10..60");
        if (minFreeDiskMiB < 64 || maxBufferMiB < 64) throw new IllegalArgumentException("Disk limits must be >=64 MiB");
        if (watchedUuids == null || discordChannelId == null) throw new IllegalArgumentException("Null config value");
        watchedUuids.forEach(java.util.UUID::fromString);
        if (!discordChannelId.isEmpty() && !discordChannelId.matches("[0-9]{1,20}")) throw new IllegalArgumentException("Invalid Discord channel ID");
    }
}
