package com.streamsim.model;

/**
 * Everything needed to start publishing.
 *
 * @param device      webcam name (Windows dshow), /dev/videoN (Linux), "N - name" (macOS) or the desktop input
 * @param captureSize optional capture size "WxH" (webcam mode / desktop region), empty = device default
 * @param filePath    video file or URL (SourceType.FILE only)
 * @param loopFile    restart the file when it ends
 * @param host        RTSP / WebRTC / HTTP: local address to bind (0.0.0.0 = all); RTP: destination IP (unicast or multicast)
 * @param port        RTSP / WebRTC / HTTP: TCP port to listen on; RTP: destination UDP port
 * @param rtspPath    RTSP mount point, e.g. "live"
 * @param width       output (encoded) width, 0 = same as source
 * @param height      output (encoded) height, 0 = same as source
 */
public record PublishConfig(
        SourceType source,
        String device,
        String captureSize,
        String filePath,
        boolean loopFile,
        OutputProtocol protocol,
        String host,
        int port,
        String rtspPath,
        int width,
        int height,
        int fps,
        int bitrateKbps) {
}
