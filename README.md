# SyncSpeaker

Turns 2-N Android phones into one synchronized speaker setup over local WiFi.
One master phone hosts an audio file; the others auto-download it and all
phones play it back together with NTP-style clock sync (~5-20ms skew typical).

## What it does (and what it doesn't)

✅ Plays the SAME audio file on multiple phones in sync
✅ Works on local WiFi or one phone's hotspot — no internet needed
✅ Master picks any audio file from device (mp3, m4a, flac, wav, ogg, etc.)
✅ Slaves auto-download via HTTP from master and cache locally

❌ Does NOT mirror YouTube / Spotify / Netflix audio. Those apps use DRM and
   explicitly block other apps from capturing their playback. There is no
   legitimate workaround on stock Android. (Android's
   `AudioPlaybackCaptureConfiguration` API exists but DRM apps opt out.)
   You'd need to download the audio separately and load it as a file.

## Build

You need:
- Android Studio (Hedgehog or newer)
- JDK 17

Steps:
1. Open this folder in Android Studio (File → Open → select `SyncSpeaker/`)
2. Let it sync Gradle (~3-5 min the first time)
3. Plug in a phone with USB debugging on, hit ▶ Run
4. Repeat on each phone you want to use

To build a standalone APK without Android Studio:
```
./gradlew assembleDebug
# APK appears at: app/build/outputs/apk/debug/app-debug.apk
# adb install app-debug.apk
```

## Use

1. **Master phone**: open app → "I'm the Master" → note the IP shown at top
   (e.g. `192.168.1.42`).
2. **Each slave phone**: open app → "I'm a Slave" → type that IP → Connect.
3. On the master, tap "Pick audio file" and choose a song. Each slave will
   show "Ready: <song>" once it has downloaded.
4. Hit ▶ Play on master. All phones start together. Hit ■ Stop to halt.

The first 1.5 seconds after Play are the scheduled "lead time" — slaves use
that window to align their clocks and arm playback. Don't worry, it's normal.

## How sync works (the short version)

- Slave repeatedly pings master with its `nanoTime` (`t1`).
- Master records receive (`t2`) and send (`t3`) timestamps and pongs back.
- Slave records receive time (`t4`).
- Offset between clocks ≈ ((t2-t1) + (t3-t4)) / 2.
- Round-trip time ≈ (t4-t1) - (t3-t2). The lower the RTT, the more accurate.
- Slave keeps the offset from the round with the lowest RTT (least jitter).
- When master sends "play at master_nanos = X", each slave converts to its
  own local nanoTime and schedules `MediaPlayer.start()` for that exact moment
  using a `Handler.postDelayed` plus a sub-millisecond busy-wait.

This is the same idea Snapcast and AirPlay use. On a typical home WiFi the
result is well below the ~20ms threshold where humans perceive "echo".

## Tips for best sync

- 5GHz WiFi is better than 2.4GHz (less interference, lower latency).
- All phones on the same access point. Mesh systems can add jitter.
- The hotspot from the master phone itself works great — direct, low latency.
- Volume/EQ settings on each phone affect perceived sync; match them up.

## File layout

```
app/src/main/java/com/syncspeaker/
  MainActivity.kt        - mode picker
  MasterActivity.kt      - host + control server + media server
  SlaveActivity.kt       - connect, sync clock, download, play
  Protocol.kt            - JSON message format
  SyncEngine.kt          - clock offset calculator
  MediaServer.kt         - tiny HTTP server (file delivery)
  SyncedPlayer.kt        - MediaPlayer wrapper with timed start
```

## Roadmap / things you could add

- Auto-discovery via NSD (Bonjour/mDNS) so slaves don't need to type IP
- Multi-track library + a queue/playlist
- Volume control per slave from the master UI
- Real-time mic streaming (works! just not for DRM apps)
- Compensate for per-device audio output latency (each device has a slightly
  different DAC delay; you can measure once and store an offset per device)
