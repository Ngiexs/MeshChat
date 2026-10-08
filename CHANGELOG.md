# Changelog

All notable changes to MeshChat will be documented in this file.
## [0.4.4-beta1] 9/10/2026

Wire protocol / parsing

1. Protocol.unpack fix — Pattern.compile(SEP).split(line, -1) instead of Regex(SEP).split(line, -1). Kotlin's Regex.split throws IllegalArgumentException on -1; Java's String.split accepts it. This was the killer bug that made every HELLO and MSG fail parse.
2. PHOTO parser fix — new parts[0] == "PHOTO" branch in three parsers:
   · MeshNode.parsePlainAndDispatch
   · MeshServer MSG decrypt block
   · ChatActivity.loadHistoryFromReplica
   Without it, PHOTO|sender|ts|ttl|mime|b64 (6 fields) matched the generic 6-field MSG-with-reply branch and rendered the base64 payload as chat text.
3. REACT listener plumbing — new onReaction(sender, targetSig, emoji) in MeshNode.Listener and MeshServer.Listener. Wired through MeshService (both dispatching listeners + the promoteToHost anonymous listener) and ChatActivity.initNetwork. Reactions arriving live now attach to their target bubble instead of rendering as standalone emoji bubbles.
4. Reaction signature consistency — MeshNode.sendChat and sendPhoto now accept an explicit tsMs. ChatActivity passes the same ts it used for its local bubble. Previously, the sender saw sender|ts1 while every peer saw sender|ts2, so reactions from the host landed on a sig the client had no row for.

Stability

5. Replica byte cap — liveBytes counter + MAX_BYTES = 50 MB. Photos are ~530 KB on disk after base64, so history now evicts at ~95 photos even before hitting the 1000-message cap. Previously, 1000 photos ≈ 500 MB.
6. Per-group notifications — MeshService notification queue and unread counter keyed by group name. notifIdForGroup and ensureChatChannel create a distinct notification ID and channel per group, so group A's notification can't clobber group B's. clearChatNotifications(group) now takes an argument.
7. onTyping thread fix — wrapped in runOnUiThread in both listener objects in ChatActivity.initNetwork. Previously MeshServer's thread pool was calling TextView.setText off the main thread. Android logged a warning; stricter ROMs will crash on it.

UI

8. Save image — new menu item on photo bubbles. Uses MediaStore on API 29+ (no runtime permission), app-private Pictures dir below 29.
9. Preview before send — showPhotoConfirmDialog shows the compressed image with dimensions and file size, Send / Cancel buttons. Previously photos sent immediately on pick.
10. Long-message collapse — bodies past 600 chars render truncated to 12 lines with a "Show more" / "Show less" link.
11. Photo bubble display cap — PHOTO_BUBBLE_MAX_W_DP = 320, PHOTO_BUBBLE_MAX_H_DP = 420. A 1024 px source no longer dominates a tablet screen.
12. Back button — double-tap-to-exit. First press shows "Press back again to exit", second press within 2 s calls finishAffinity(). Service keeps running. Previously it nagged indefinitely.

Bug fixes

13. TTL on photos — sweepReplicaExpiry now reads ttl from field 3 for PHOTO plaintexts. Previously disappearing photos stayed in the replica forever.
14. Delete-for-me reliability — bubble.tag = outer pins the row container; deleteForMe reads the tag directly instead of walking the parent chain. Fallback to the old hop-walk for pre-tag bubbles.
15. onNewIntent group refresh — the picker or a notification tap can switch active groups; header/drawer labels now refresh.
16. Tick asymmetry fix — removed the host's self-ACK from sendCurrentText and sendPhotoBytes. Both host and client now show the same progression: ◯ → ✓ 1 → ✓ 2 → ✓✓ (read). Previously the host's own message showed ✓✓ 2 while the client's showed ✓ 1, both meaning "delivered, not read".


## [0.4.3_hotFix-beta1] - 2026-10-08
### Fixed
- Hotfix for critical app issues.
- Resolved Gradle build failures (AAPT2, profileinstaller, duplicate resources).
- Fixed merge conflicts in `activity_chat.xml`, `settings.gradle.kts`, and Kotlin files.
- Cleaned up `AndroidManifest.xml` parsing errors.

## [0.4.1-beta1] - 2026-10-06
### Changed
- Bumped version code to 3.
- Minor bug fixes and stability improvements.

## [0.4.0-beta1] - 2026-10-05
### Added
- Initial beta release.
- Peer-to-peer encrypted group chat over Tor.
- AES-256-GCM encryption.
- Automatic backup node failover.
