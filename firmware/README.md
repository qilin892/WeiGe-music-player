# ESP32 固件补丁

让 [xiaozhi-esp32](https://github.com/78/xiaozhi-esp32) 设备接受局域网推送的音乐。

本目录**不包含**上游完整源码，只提供改动补丁，避免重复上游代码。

## 基线

- 上游：`78/xiaozhi-esp32`
- 版本：**v2.5.1**
- 验证硬件：微雪 `esp32-s3-touch-amoled-1.8`（ESP32-S3 / 8MB PSRAM / 16MB Flash）

## 应用

```bash
git clone https://github.com/78/xiaozhi-esp32.git
cd xiaozhi-esp32
git checkout v2.5.1
git am /path/to/0001-add-music-http-and-remote-play.patch
```

## 改动内容

| 文件 | 类型 | 说明 |
|---|---|---|
| `main/music_http_server.cc` | 新增 | 设备侧 HTTP 服务（默认 `:8080`）：`/play?url=`、`/stop`、`/status` |
| `main/music_http_server.h` | 新增 | 对应头文件 |
| `main/mcp_server.cc` | 修改 | 新增 `self.music.play` 与 `self.music.stop` |
| `main/application.cc` | 修改 | `PlayMusic` 支持对话中插播；`StopMusic` 不再依赖状态机；`music_playing_` 保护状态 |
| `main/application.h` | 修改 | 新增 `music_playing_` 成员 |
| `main/CMakeLists.txt` | 修改 | 注册新增源文件 |

## 状态机要点

设备的状态机只允许：

```
listening ──▶ speaking ──▶ listening
     │            │
     └────────────┴──▶ idle ──▶ notifying
```

即 `listening/speaking` **不能**直接跳到 `notifying`，必须先回 `idle`。而语音点歌时设备必然处于 `listening` 或 `speaking`，所以补丁在 `PlayMusic` 里做三件事：

1. 放宽状态限制，允许从对话中进入
2. 若正在放别的歌，先 `StopNotification()`
3. 仍在对话 → `CloseAudioChannel()` + 关语音处理 + 切 `idle` + 等 150ms，再 `StartNotification()`

同时用 `music_playing_` 标志挡住 `OnAudioChannelClosed` 回调里的 `SetDeviceState(kDeviceStateIdle)` —— 否则会出现「音乐还在响，但停止按钮无效」。

## 编译

ESP-IDF ≥ 6.0（本项目用 6.1 验证）：

```bash
idf.py set-target esp32s3
idf.py build
idf.py -p COM5 flash
```

不擦 NVS 的情况下，WiFi 配网与激活状态都会保留。
