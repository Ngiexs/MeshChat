# MeshChat

A peer-to-peer encrypted group chat that runs entirely over Tor. No central server, no account, no phone number.

## What it is

Every member of a group shares three secrets: a group name, a passphrase, and a Tor `.onion` address. Messages are encrypted with AES-256-GCM using a key derived from the passphrase, then routed through the Tor network so no one sees your IP address.

One phone in the group acts as the host, relaying messages between the other members. If the host goes offline, a designated backup node takes over automatically within about a minute. The `.onion` address stays the same, so nobody has to rejoin or re-share anything.

## Features

- End-to-end encrypted group chat (AES-256-GCM, PBKDF2-SHA256 with 200,000 iterations)
- No account, no phone number, no email
- Traffic routed through Tor — no central server
- Automatic failover if the host disconnects
- Disappearing messages (30s, 5m, 1h, 1d)
- Reply to specific messages
- Delivery receipts
- Typing indicators
- QR code sharing for joining a group
- Multiple groups per device (one active at a time)
- Runs as an Android foreground service, survives reboot

## Limitations

- **One active group at a time.** Switching groups stops the current session and starts a new one.
- **Host process death is detected after ~60 seconds.** During that window, messages sent to the host may not be delivered.
- **The host phone must stay reasonably active.** Android will throttle background services on some devices; whitelist MeshChat in battery settings for best results.
- **Voice, files, and message search are not implemented.** This is a text-only beta.

## How it works

1. **Group identity** — the Tor hidden-service keypair is generated once and shared with every member via the group credentials (QR code or clipboard).
2. **Message encryption** — plaintext is packed as `MSG\1sender\1timestamp\1body\1ttl`, encrypted with AES-256-GCM, and sent as `MSG|msgId|cipher` over the Tor circuit.
3. **Host relay** — the host accepts client connections on port 8888, appends each message to its local replica, forwards to all other clients, and ACKs back to the sender.
4. **Failover** — if the host stops responding, each backup runs an election and the winner rebinds port 8888 to the same `.onion` using the shared keypair.

## Building

Requirements:

- Android SDK 35
- JDK 17
- Gradle 8.x (wrapper included)
- The `libtor.so` native library (Tor 0.4.9.11, arm64-v8a) must be present at `app/src/main/jniLibs/arm64-v8a/libtor.so`

```bash
git clone https://github.com/YOUR_USERNAME/MeshChat.git
cd MeshChat
./gradlew assembleDebug
