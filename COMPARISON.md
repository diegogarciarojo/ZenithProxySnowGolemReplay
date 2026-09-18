# Architecture comparison

The implementation uses SnowGolemReplay's recording and delivery architecture,
with the Discord field arrangement originally reviewed in DeathRecorder.

## Reference projects

- ZenithProxy: cached world snapshots, packet codecs, ReplayMod conversion, native
  embeds, theme colors, command usage and toggle responses.
- ZenithProxyExamplePlugin: plugin, module, configuration, command registration,
  and Gradle integration.
- ZenithProxyRedstoneNotify: world-event monitoring and Discord notification patterns.
- ZenithProxyAntiRompedorDeGranjas: native command responses and structured alerts.
- ZenithProxySnowGolemDeathRecorder: death alert field arrangement and manual clip concept.

## Recording and delivery differences

The reviewed DeathRecorder revision (29a7f17) can treat entity removal as a death
when the entity is still cached, ignores additional capture triggers while saving,
and removes its oldest recording after a capture. A second death soon afterward
may therefore lack the requested pre-event history. Its stop path deletes temporary
sessions, including sessions awaiting post-event capture. Its uploader uses API v1,
a single chunk signature, and does not inspect the completion response body.

SnowGolemReplay retains the window anchoring the requested history until a newer
window can replace it. It supports grouped death markers, confirms death using
entity status/health/pose, preserves pending incidents on disconnect, and uses a
persistent multipart API v2 uploader that requires explicit completion confirmation.

Version 1.1.0 introduced native Discord delivery with persistent message/channel
IDs, manual clips, stale entity identity correction, and truncated packet rejection.
Version 1.1.1 standardizes commands, notifications, errors, fallback messages, and
documentation in English, and uses ZenithProxy's configured theme colors.

Default recordings include 60–75 seconds of pre-event history after warm-up and
10 seconds after the last grouped event. World snapshots are required for a
playable replay. No client-side recorder can reconstruct unobserved server events.

## Source revisions reviewed

- [ZenithProxy 4fe4508](https://github.com/rfresh2/ZenithProxy/tree/4fe4508c3c4431c0fba79918f33b37381faa779c)
- [ExamplePlugin d0c22e3](https://github.com/rfresh2/ZenithProxyExamplePlugin/tree/d0c22e35b731f376f04e21a3adb44102e64ea3cc)
- [RedstoneNotify 20386a4](https://github.com/IceTank/ZenithProxyRedstoneNotify/tree/20386a4669e90d9d5d1eca8f92330a65ca5726f4)
- [AntiRompedor 785f329](https://github.com/diegogarciarojo/ZenithProxyAntiRompedorDeGranjas/tree/785f329b949982cfadc108fcef76b9463522a975)
- [DeathRecorder 29a7f17](https://github.com/diegogarciarojo/ZenithProxySnowGolemDeathRecorder/tree/29a7f17388c207f4ecc0b0b2c0104f87164d580f)
- [SnowGolemReplay base 3dafeae](https://github.com/diegogarciarojo/ZenithProxySnowGolemReplay/tree/3dafeae)
- [file.kiwi API](https://file.kiwi/api)

Binary compatibility is tested against published ZenithProxy 3.7.0+1.21.4.
See VALIDATION.md for the distinction between synthetic tests and live operation.
