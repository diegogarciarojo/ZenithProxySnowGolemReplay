# ZenithProxySnowGolemReplay

Records ReplayMod footage before and after snow golem deaths. Saves the replay,
incident report, and death markers locally, with optional Discord and file.kiwi uploads.

[Download the latest release](https://github.com/diegogarciarojo/ZenithProxySnowGolemReplay/releases/latest)

## Installation

Requires **ZenithProxy 3.7.0+1.21.4**, Java channel, and **Java 21 or newer**.
Use **ReplayMod for Minecraft 1.21.4** to open recordings. The proxy's internal
Minecraft version determines compatibility, even when ViaVersion connects to a different server version.

Place `ZenithProxySnowGolemReplay-1.1.1.jar` in `plugins/`, replace the previous
version, and restart ZenithProxy. Install only one snow golem recorder:
DeathRecorder also registers the `golemreplay` alias. AntiRompedorDeGranjas,
the built-in ReplayMod module, and VisualRange can remain installed.

The module is enabled by default and uses ZenithProxy's existing Discord bot.
Commands require account-owner permissions and must be sent to the configured
control channel using its configured prefix. The default prefix is `.`.
The bot needs permission to send messages, embed links, and attach files.
No Node.js installation or file.kiwi API key is required.

## Commands

`.golemreplay` displays the native **Invalid command usage** response with current
settings and command usage. Successful commands use the primary theme color;
syntax and execution errors use ZenithProxy's native error responses.

| Command | Description |
|---|---|
| `golemreplay on/off` | Enable or disable monitoring and recording. |
| `golemreplay status` | Show recording state, available history, settings, and the last recording issue. |
| `golemreplay golems` | List up to 30 loaded snow golems matching the watch filter. |
| `golemreplay clip` | Save available history and the configured post-event interval without simulating a death. |
| `golemreplay test` | Record the next 60 seconds and deliver the replay, including any observed deaths. |
| `golemreplay buffer <seconds>` | Set pre-event history: 10–300 seconds; default 60. |
| `golemreplay post <seconds>` | Set post-event recording: 1–60 seconds; default 10. |
| `golemreplay checkpoint <seconds>` | Set snapshot interval: 10–60 seconds; default 15. |
| `golemreplay discord on/off` | Toggle Discord replay attachments and links. Command replies remain available. |
| `golemreplay kiwi on/off` | Toggle file.kiwi uploads. |
| `golemreplay channel <id/default>` | Set the delivery channel; commands still use ZenithProxy's control channel. |
| `golemreplay watch all` | Watch all loaded snow golems. |
| `golemreplay watch add <uuid>` | Add a golem to the watch filter. |
| `golemreplay watch remove <uuid>` | Remove a golem from the watch filter. An empty filter watches all golems. |
| `golemreplay watch list` | Show the watch filter. |
| `golemreplay minFreeDisk <MiB>` | Set minimum free disk space: 64–1048576 MiB; default 256. |
| `golemreplay maxBufferDisk <MiB>` | Set buffer disk limit: 64–1048576 MiB; default 2048. |
| `golemreplay retry` | Retry pending or failed deliveries without repeating confirmed steps. |

Settings are stored in `plugins/config/snow-golem-replay.json`. Changes to timing,
disk limits, or the watch filter restart the buffer. These changes are rejected
during a manual test; disabling the module can interrupt and preserve that test.

## Recording

Each checkpoint starts an independent recording with a complete initial world
snapshot. Simply discarding old packets would lose the chunks, entities, and
configuration needed to replay the scene.

With the default settings and a full buffer, a death recording includes **60 to
approximately 75 seconds before death** and **10 seconds afterward**. The selected
window remains available until another window can provide the required history.
Several deaths can share a replay, with one marker per golem and a post-event
interval extending after the last grouped death.

After startup, reconnection, or a buffer reset, available history may be shorter.
Partial history is identified in the report and Discord embed. Disconnects and
dimension changes preserve pending incidents and identify the interrupted ending.

Death confirmation uses a snow golem's death event, zero health, or dying pose.
Damage alone, another species dying, and entity removal do not confirm a death.
UUID tracking prevents stale identity or damage from being assigned to reused IDs.

`clip` preserves available history without consuming the rolling buffer.
`test` starts a separate forward recording even if the rolling buffer is empty
or suspended, provided ZenithProxy is online, outside the queue, and has enough
disk space. Both commands fail explicitly when their requirements are not met.
Manual markers never count as deaths. Normal monitoring continues during tests.

**ZenithProxy must remain online and receive the golem's area.** Events that
occurred while disconnected, queued, or outside the server's tracking range
cannot be reconstructed. A replay is Minecraft data, not an MP4 video.

## Discord and uploads

All messages use native ZenithProxy embeds. Death alerts retain the existing
layout: title, coordinates and damage evidence in adjacent fields, replay file,
recording details, markers, and download link. Commands and manual recordings use
the primary theme color; death alerts use the configured error color.

The replay attachment is sent first. After file.kiwi confirms completion, its link
is added to the same embed while retaining the attachment. Message and channel IDs
are persisted across restarts. A deleted message is replaced with a new link embed.
Older delivery jobs remain supported; jobs without structured evidence receive
an English fallback rather than repeating a saved legacy summary.

Discord failures do not prevent file.kiwi uploads. Files exceeding the guild's
attachment limit remain local and can still be uploaded to file.kiwi. Deliveries
retry up to five times with increasing delays. Lost Discord responses can
occasionally cause duplicate messages.

The uploader uses API v2, AES-128-GCM/RFC 8188 encryption, sequential signed chunk
uploads, resumable state, timeouts, and completion verification. The URL fragment
contains the download key. See the [file.kiwi API documentation](https://file.kiwi/api)
for current download policies. Keep local copies.

## Evidence and storage

Files are saved under `replays/snow-golem/incidents/`:

- `golem-<id>.mcpr`: replay containing `markers.json` and `golem-incident.json`.
- `.mcpr.incident.json`: incident report readable without opening the replay.
- `.mcpr.delivery.json`: delivery progress and errors.
- `.mcpr.kiwi.json`: resumable upload state and access data.

Reports include UTC/local time, dimension, position, UUID, death confirmation,
replay timestamp, and up to 128 recent damage events. Where available, damage
records identify the damage type, source entity, direct entity, and their UUIDs.
Rain, fire, and the block at the golem's feet are observations.
**Recent damage is evidence, not necessarily the final cause of death.**

Replays contain the recorded area and visible Minecraft data. The full download
link and upload state can grant access to that recording.

Expired buffer windows without incidents are deleted. Incident files remain local
after upload and require storage management. Low disk space suspends recording.
Unfinished files from crashes or writer errors are retained for diagnosis and count
toward the buffer limit. Abrupt process termination or power loss can leave an
unrecoverable recording; normal shutdown attempts to finalize pending incidents.

Approximately `ceil(buffer/checkpoint)+1` writers run concurrently, plus pending
incident/test windows. Longer history or shorter checkpoints increase CPU and disk
use. Writer queues are bounded and report failures.

## Development

Build with JDK 25 and the included Gradle wrapper. Output uses Java 21 bytecode.
The official plugin development integration is pinned to 1.2.0 and ZenithProxy
to 3.7.0+1.21.4.

`gradlew build` runs the regular tests and creates the JAR in `build/libs/`.
`gradlew build -PrealTimeTest=true` also runs the 77-second recording test.

See [VALIDATION.md](VALIDATION.md) for validation scope and [COMPARISON.md](COMPARISON.md)
for architecture decisions. Source adapted from ZenithProxy remains under AGPL-3.0;
see LICENSE and THIRD_PARTY_NOTICES.md.
