# Third-party notices and references

## ZenithProxy

This plugin is distributed under AGPL-3.0-only; see LICENSE. The file
`BufferedReplayRecording.java` is adapted from `ReplayRecording.java` in
ZenithProxy **3.7.0+1.21.4**, copyright ZenithProxy contributors, under AGPL-3.0.
It preserves the login/configuration/world snapshot and outgoing-player replay
conversion, with a bounded queue, observable write failures and a corrected
close sequence that drains the writer without holding its monitor.

Source: https://github.com/rfresh2/ZenithProxy
Release source archive: https://maven.2b2t.vc/releases/com/zenith/ZenithProxy/3.7.0+1.21.4/ZenithProxy-3.7.0+1.21.4-sources.jar

Project structure and Gradle integration follow the CC0 example:
https://github.com/rfresh2/ZenithProxyExamplePlugin

The following plugins were reviewed for module, configuration and Discord patterns:

- https://github.com/diegogarciarojo/ZenithProxyAntiRompedorDeGranjas
- https://github.com/IceTank/ZenithProxyRedstoneNotify (CC0)
- https://github.com/diegogarciarojo/ZenithProxySnowGolemDeathRecorder
  (reference for Discord title, colors and field arrangement; the delivery queue,
  retries, recording and upload implementation remain SnowGolemReplay's).

## file.kiwi

The Java uploader implements the public v2 API and RFC 8188. It does not require
Node.js, an API key, or any bundled JavaScript dependency.

- API: https://file.kiwi/api
- SDK reference: https://github.com/file-kiwi/node (MIT)
- Encryption reference: https://github.com/SocketDev/wormhole-crypto (MIT)
- Specification: https://www.rfc-editor.org/rfc/rfc8188

`kiwi-vectors.json` contains hashes of independent test ciphertext generated with
wormhole-crypto 0.3.1 (the SDK's encryption dependency). Key bytes are 0..15;
salt bytes are 31..16; plaintext byte i is i modulo 251; record size is 65536.
They cover single and multiple records and exact record boundaries.
