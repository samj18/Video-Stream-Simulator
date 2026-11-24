# Video Stream Simulator

JavaFX + Spring Boot desktop app that **publishes** a webcam, the desktop or a video file over
**RTSP**, **RTP**, **WebRTC** (browser) or **HTTP with Range requests**, and **receives / displays** streams
(from this app on another PC, VLC, ffmpeg, IP cameras ...).

```
 webcam / desktop / file --FFmpeg grab--> H.264 (libx264) --RTP packetizer--+--> RTSP server  rtsp://IP:8554/live
                                                                            +--> RTP push     udp  IP:5004
 webcam / desktop / file --I420--> libwebrtc ---------------------------> browsers  http://IP:8080/   (WebRTC / WHEP)
 video file (as is) ------------------------------------------------------> http://IP:8080/video     (HTTP Range)
 RTSP / RTP / HTTP / any URL --FFmpeg grab + decode--> JavaFX preview;  WebRTC (WHEP) --webrtc-java--> JavaFX preview   (Receiver tab)
```

## Requirements
* JDK 17+ and Maven 3.9+
* Internet on the first build (JavaFX + JavaCV/FFmpeg natives, ~250 MB for all platforms)

## Run
```bash
mvn javafx:run                       # development
mvn clean package && java -jar target/video-stream-simulator-1.0.0.jar     # fat jar (built for the OS you build on)
```
Only download the natives of one OS (much smaller): `mvn -Djavacpp.platform=windows-x86_64 clean package`
(`linux-x86_64`, `macosx-arm64`, `macosx-x86_64`).

## Publisher tab
| Setting | Meaning |
|---|---|
| Source | Webcam, Desktop (screen capture) or Video file / URL (looped, paced in real time) |
| Device | Webcam name (Windows/DirectShow), `/dev/videoN` (Linux), `index - name` (macOS). **Refresh** re-scans. Editable. |
| Protocol **RTSP** | The app is the server. *Bind IP* = `0.0.0.0` (all NICs) or one local IP, *Port* default 8554, *RTSP path* default `live`. Clients connect to `rtsp://<this-pc-ip>:8554/live` (UDP or TCP transport). |
| Protocol **RTP** | The app pushes UDP/RTP to *Destination IP : Port* (unicast or multicast e.g. 239.255.0.1). A plain RTP stream carries no description, so the app generates an **SDP** - save it and open it in VLC. |
| Protocol **WebRTC** | Starts a web server (default port 8080). Open `http://<this-pc-ip>:8080/` in Chrome / Edge / Firefox / Safari - the page connects automatically. Signalling is **WHEP** (`POST /whep`), so WHEP players / media servers work too. Up to 8 viewers; libwebrtc encodes per viewer (VP8/H.264, adaptive bitrate), so *Bitrate* is ignored. |
| Protocol **HTTP** | Serves a local video file **as is** (no re-encoding) at `http://<ip>:8080/video` with full **HTTP Range** support (`206 Partial Content`, `416`, `If-Range`, `HEAD`): seek in VLC, ffplay, `<video>`, curl. `http://<ip>:8080/` shows an HTML5 player. |
| Resolution / FPS / Bitrate | Encoder output. Resolution `Same as source` keeps the source size. |

### Watch it in VLC
* **RTSP:** Media -> Open Network Stream -> `rtsp://<ip>:8554/live`
* **HTTP file:** Media -> Open Network Stream -> `http://<ip>:8080/video`
* **RTP:** click *Save SDP file...* in the app, copy `stream.sdp` to the receiving PC and open it with VLC
  (the SDP's `c=` address must be the receiving PC's IP, so set *Destination IP* to that PC before starting).

## Receiver tab
* **RTSP** - `rtsp://ip:port/path`, optional TCP transport.
* **RTP** - listens on a local UDP port (H.264, payload type 96): what the Publisher's RTP mode sends.
* **HTTP / file URL (Range, seekable)** - e.g. `http://ip:8080/video` from the Publisher's HTTP mode. FFmpeg seeks with Range requests. A player bar appears under the video: **Pause/Play, -10 s, +10 s**, a seek slider (drag and release) and `mm:ss / mm:ss`. Needs a sender that serves a *file*; a live stream has no duration and plays without the bar.
* **WebRTC (WHEP)** - e.g. `http://ip:8080/whep` from the Publisher's WebRTC mode (or any WHEP server such as MediaMTX). Video only; no seeking (live).
* **Any URL / SDP file** - anything FFmpeg opens (`udp://@:1234`, a `.sdp` file ...).
* Live RTSP / RTP / WebRTC streams cannot be seeked: they are a live feed, not a file. Only the HTTP file mode can go backward and forward.
* *Auto-reconnect* keeps retrying while the sender is not up yet.

Typical test on one PC: Publisher -> RTSP -> Start, then Receiver -> `rtsp://127.0.0.1:8554/live` -> Start.
Two PCs: Publisher on PC-A, Receiver (or VLC) on PC-B using PC-A's IP. Open the firewall for TCP 8554 (RTSP) / the UDP port.

## Defaults
`src/main/resources/application.yml` (`streamsim.*`): ports, path, fps, bitrate, RTP packet size.

## Code map
| Package | Purpose |
|---|---|
| `web` | `HttpMediaServer` (JDK `HttpServer`, no extra dependency), `RangeFileResponder` (RFC 9110 ranges), WHEP endpoint, viewer page |
| `webrtc` | `WebRtcHub` - the only class that uses `webrtc-java` (answers WHEP offers, pushes I420 frames) |
| `net` | `H264RtpPacketizer` (RFC 6184, FU-A), `RtspServer` (OPTIONS/DESCRIBE/SETUP/PLAY, UDP + TCP-interleaved), `UdpRtpOutput`, `SdpBuilder`, `H264RtpStream` (fan-out) |
| `capture` | `I420Converter` (BGR -> I420), `CaptureSourceFactory` (dshow/v4l2/avfoundation, gdigrab/x11grab, files), `DeviceService` (device + IP discovery) |
| `service` | `StreamPublisherService`, `StreamReceiverService` (Spring beans, worker threads) |
| `ui` | `MainView` (JavaFX tabs), `VideoPane` |

## Theme
`src/main/resources/app.css`: background `#061228`, buttons `#5b3df5` (hover `#7358ff`). Change the colours in that one file.

## Notes
* WebRTC / firewall: the viewer page needs TCP 8080; the media itself flows over UDP on ephemeral ports chosen by libwebrtc - allow the app through the OS firewall.
* WebRTC needs `webrtc-java` natives for your OS (downloaded by Maven for the OS you build on). If it fails to load, add the classifier shown in `pom.xml`.
* Custom `jlink` / `jpackage` runtime images must include the `jdk.httpserver` module (`--add-modules jdk.httpserver`).
* H.264 encoder: libx264 (from `ffmpeg-platform-gpl`; this makes the distributed app GPL), with fallbacks
  (openh264, NVENC, QSV, AMF, VideoToolbox) tried automatically.
* Linux desktop capture uses X11 (`x11grab`); on Wayland use an X11 session or XWayland.
* macOS: grant Screen Recording / Camera permission to the terminal or app.
* `ffmpeg` bundled with JavaCV is used for device discovery; no system FFmpeg is required.
