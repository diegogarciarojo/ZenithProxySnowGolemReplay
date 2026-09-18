# Validation

## Target

ZenithProxy **3.7.0+1.21.4**, official plugin development integration **1.2.0**,
Gradle **9.7.1**, JDK **25** for compilation, and Java **21** bytecode.
Open recordings with ReplayMod for Minecraft **1.21.4**.

## Automated coverage

The suite covers rolling history, repeated deaths, disconnect handling, manual
recordings, packet serialization, marker compatibility, truncated archives,
entity ID reuse, disk limits, command settings, native embed generation,
persistent delivery retries, deleted messages, legacy jobs, and upload limits.

file.kiwi tests use a local HTTP server to check API v2 requests, signatures,
headers, chunk ordering, resumption, and explicit completion confirmation.
Encryption is checked against independent SDK vectors.

The optional 77-second test uses real ZenithProxy/MCProtocolLib classes with a
synthetic cached world. It records damage around five seconds and death around
65 seconds, exports a replay, decodes it with ZenithProxy's ReplayReader, and
requires at least 60 seconds of pre-death history. It is not a live Minecraft test.

## Validation limits

- Discord transport tests generate real Zenith/JDA embeds but do not send messages
  through the user's bot. Live permissions, appearance, and delivery remain to be
  checked in that installation.
- Packet decoding and marker checks do not replace visual playback in ReplayMod.
- Real 2b2t behavior depends on the chunks, entities, and damage packets delivered
  to ZenithProxy.
- No new live file.kiwi upload is performed by the local test suite.
- Other ZenithProxy versions and release channels are not covered.

Version 1.1.0 passed 32 distinct local cases and loaded successfully on Java 21.
Version **1.1.1** passed **33 tests**, with zero failures, errors, or skips, using
`gradlew build -PrealTimeTest=true --max-workers 2` on September 18, 2026.
This includes the 77-second test and native command-manager checks for no-argument
help, structured status, toggle replies, invalid usage, and execution errors.
Embed tests also verify the configured error theme color for death alerts.

The production 1.1.1 JAR loaded through `com.zenith.ProxyLaunchWrapper` on
Java 21.0.11 with published ZenithProxy dependencies: `Plugin Loaded`, version
`1.1.1`, followed by `ZenithProxy started!`. The isolated smoke process had
Discord, uploads, automatic connections, and the inbound server disabled and
was stopped after verification. No user credentials were used.
