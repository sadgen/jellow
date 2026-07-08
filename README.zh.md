# Jellow

[**English**](README.md) | [**中文**](README.zh.md)

**Jellow** 是一个第三方的 [Jellyfin](https://jellyfin.org) Android 客户端，基于 [Findroid](https://github.com/jarnedemeulemeester/findroid) 修改增强。

> 个人维护的分支，非官方项目。

## 相对 Findroid 新增功能

- **mpv 播放器内核** — 支持 ExoPlayer 和 mpv 双后端切换
- **转码播放** — 支持服务端转码，可调整码率
- **码率即时生效** — 点击左下角播放信息浮层，无需重启即可切换码率
- **播放信息叠加层** — 实时显示编码格式、分辨率、转码码率、下行网速、流量统计
- **陀螺仪操控** — 身体转动控制视频平移/缩放
- **VR 模式** — 360° 全景视频播放
- **画中画** — 支持手势控制
- **跳过片头/片尾** — 手动跳过和自动跳过（需 Jellyfin 10.10+）
- **Trickplay 预览** — 拖动进度条时显示缩略图预览（需 Jellyfin 10.9+）
- **锁屏、横竖屏锁定、章节标记**

## 构建

```bash
export JAVA_HOME=/path/to/jdk-21.0.10+7
./gradlew assembleJellowDebug
```

APK 输出位置：`app/phone/build/outputs/apk/jellow/debug/phone-jellow-arm64-v8a-debug.apk`

## 许可证

[GPLv3](LICENSE)
