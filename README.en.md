# Jellow

[**English**](README.en.md) | [**中文**](README.md)

**Jellow** is a third-party Android application for [Jellyfin](https://jellyfin.org), forked from [Findroid](https://github.com/jarnedemeulemeester/findroid). It provides a native user interface to browse and play movies and series.

> This is a personal fork with additional features. Not affiliated with the official Findroid project.

## Additional features vs Findroid

- **mpv player backend** alongside ExoPlayer
- **Transcoding support** with adjustable bitrate
- **Instant bitrate change** — tap the playback info overlay to change bitrate without restarting playback
- **Playback info overlay** — codec, resolution, transcoding bitrate, network speed, and data usage display
- **Gesture controls** — gyroscope-based video panning
- **VR mode** — 360° video via spherical GL surface view
- **Picture-in-picture** with gesture controls
- **Skip segments** — intro/credits skip (requires Jellyfin 10.10+)
- **Trickplay** — thumbnail previews on seek (requires Jellyfin 10.9+)
- **Lock screen**, orientation toggle, chapter markers

## Build

```bash
export JAVA_HOME=/path/to/jdk-21.0.10+7
./gradlew assembleJellowDebug
```

APK output: `app/phone/build/outputs/apk/jellow/debug/phone-jellow-arm64-v8a-debug.apk`

## License

[GPLv3](LICENSE)
